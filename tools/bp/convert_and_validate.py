#!/usr/bin/env python3
"""Build and validate the B19 blood-pressure TFLite model.

Canonical sources:
- lstm_ppg_nonmixed.h5 (Zenodo 5590603), MD5 189bc9c1e6b946a0b91f936a07848801
- recovered modelo_pa.py math as the numerical oracle
- tools/bp/golden_bp15_windows.json historical BP15 windows

This script intentionally converts using TFLITE_BUILTINS only. If conversion
requires SELECT_TF_OPS/Flex, it fails instead of silently widening runtime
requirements.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import urllib.request

import h5py
import numpy as np
from scipy.signal import butter, filtfilt, resample_poly
from scipy.special import expit
import tensorflow as tf

MODEL_URL = "https://zenodo.org/records/5590603/files/lstm_ppg_nonmixed.h5?download=1"
MODEL_MD5 = "189bc9c1e6b946a0b91f936a07848801"

ROOT = Path(__file__).resolve().parents[2]
GOLDEN_PATH = ROOT / "tools" / "bp" / "golden_bp15_windows.json"
BUILD_DIR = ROOT / "build" / "bp"
H5_PATH = BUILD_DIR / "lstm_ppg_nonmixed.h5"
TFLITE_PATH = ROOT / "app" / "src" / "main" / "assets" / "lstm_ppg_nonmixed.tflite"
STAGES_PATH = ROOT / "app" / "src" / "test" / "resources" / "bp_golden_stages.json"
REPORT_PATH = ROOT / "validation" / "bp" / "tflite_validation_report.json"

EXPECTED_ARCH = [
    ("InputLayer", "input_4"),
    ("Conv1D", "conv1d_48"),
    ("Bidirectional", "bidirectional"),
    ("Bidirectional", "bidirectional_1"),
    ("Bidirectional", "bidirectional_2"),
    ("Dense", "dense_5"),
    ("Dense", "SBP"),
    ("Dense", "DBP"),
]


def ensure_h5() -> None:
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    if not H5_PATH.exists():
        print("Downloading canonical H5 from Zenodo...")
        tmp = H5_PATH.with_suffix(".download")
        with urllib.request.urlopen(MODEL_URL, timeout=120) as source, tmp.open("wb") as target:
            while True:
                block = source.read(1024 * 1024)
                if not block:
                    break
                target.write(block)
        tmp.replace(H5_PATH)

    md5 = hashlib.md5(H5_PATH.read_bytes()).hexdigest()
    if md5 != MODEL_MD5:
        raise RuntimeError(f"H5 MD5 mismatch: {md5}")
    print("H5 MD5 OK:", md5)


def inspect_and_assert_architecture() -> dict:
    with h5py.File(H5_PATH, "r") as f:
        model_config = f.attrs["model_config"]
        if isinstance(model_config, bytes):
            model_config = model_config.decode("utf-8")
        cfg = json.loads(model_config)

        layers = cfg["config"]["layers"]
        actual = [(layer["class_name"], layer["config"]["name"]) for layer in layers]
        if actual != EXPECTED_ARCH:
            raise RuntimeError(f"Unexpected H5 architecture: {actual}")

        conv = layers[1]["config"]
        if not (
            conv["filters"] == 32
            and conv["kernel_size"] == [5]
            and conv["padding"] == "causal"
            and conv["activation"] == "relu"
        ):
            raise RuntimeError(f"Unexpected conv1d_48 config: {conv}")

        expected_bilstm = [
            ("bidirectional", 64, True),
            ("bidirectional_1", 64, True),
            ("bidirectional_2", 32, False),
        ]
        for index, (name, units, return_sequences) in zip((2, 3, 4), expected_bilstm):
            layer = layers[index]["config"]
            inner = layer["layer"]["config"]
            if layer["name"] != name or layer["merge_mode"] != "concat":
                raise RuntimeError(f"Unexpected {name} wrapper config")
            if inner["units"] != units or inner["return_sequences"] != return_sequences:
                raise RuntimeError(f"Unexpected {name} LSTM config: {inner}")

        dense = layers[5]["config"]
        if dense["units"] != 128 or dense["activation"] != "linear":
            raise RuntimeError(f"Unexpected dense_5 config: {dense}")

        for idx, name in ((6, "SBP"), (7, "DBP")):
            d = layers[idx]["config"]
            if d["units"] != 1 or d["activation"] != "linear":
                raise RuntimeError(f"Unexpected {name} config: {d}")

        datasets = {}
        def visitor(name, obj):
            if isinstance(obj, h5py.Dataset):
                datasets[name] = list(obj.shape)
        f["model_weights"].visititems(visitor)

        return {
            "keras_version": (
                f.attrs["keras_version"].decode()
                if isinstance(f.attrs["keras_version"], bytes)
                else str(f.attrs["keras_version"])
            ),
            "backend": (
                f.attrs["backend"].decode()
                if isinstance(f.attrs["backend"], bytes)
                else str(f.attrs["backend"])
            ),
            "layers": actual,
            "weight_datasets": datasets,
        }


class Oracle:
    """Recovered modelo_pa.py math, kept deliberately independent of TensorFlow."""

    def __init__(self):
        self.w = {}
        with h5py.File(H5_PATH, "r") as f:
            f["model_weights"].visititems(
                lambda n, o: self.w.update({n: o[:]})
                if isinstance(o, h5py.Dataset)
                else None
            )
        self.ba = butter(4, [0.5, 8], btype="bandpass", fs=125)

    def preprocess_stages(self, raw):
        raw = np.asarray(raw, dtype=float)
        if raw.shape != (700,) or not np.isfinite(raw).all():
            raise ValueError("Ventana incompleta/no finita")
        if raw.min() < 8000 or raw.max() >= 260000:
            raise ValueError("Sin contacto o cerca de saturacion")

        resampled = resample_poly(raw, 5, 4)
        filtered = filtfilt(*self.ba, resampled)
        sd = float(filtered.std())
        if sd < 1e-6:
            raise ValueError("Onda constante")
        normalized = ((filtered - filtered.mean()) / sd).astype(np.float32)
        return resampled, filtered, normalized

    def weight(self, prefix, name):
        return next(
            v
            for k, v in self.w.items()
            if k.startswith(prefix + "/") and k.endswith("/" + name + ":0")
        )

    def lstm(self, x, prefix, reverse):
        key = prefix + "/" + prefix + "/" + ("backward_" if reverse else "forward_")
        k = next(k for k in self.w if k.startswith(key) and k.endswith("/kernel:0"))
        base = k[:-len("kernel:0")]
        K = self.w[base + "kernel:0"]
        R = self.w[base + "recurrent_kernel:0"]
        bias = self.w[base + "bias:0"]
        n = R.shape[0]

        h = np.zeros(n, dtype=np.float32)
        c = h.copy()
        out = np.empty((len(x), n), np.float32)
        iterator = range(len(x) - 1, -1, -1) if reverse else range(len(x))
        for j in iterator:
            z = x[j] @ K + h @ R + bias
            i = expit(z[:n])
            fg = expit(z[n:2*n])
            g = np.tanh(z[2*n:3*n])
            o = expit(z[3*n:])
            c = fg * c + i * g
            h = o * np.tanh(c)
            out[j] = h
        return out, h

    def predict_normalized(self, x):
        K = self.weight("conv1d_48", "kernel")[:, 0, :]
        bias = self.weight("conv1d_48", "bias")
        y = np.tile(bias, (len(x), 1))
        for t in range(5):
            shift = 4 - t
            y[shift:] += x[:len(x)-shift, None] * K[t]
        y = np.maximum(y, 0)

        for prefix in ("bidirectional", "bidirectional_1"):
            a, _ = self.lstm(y, prefix, False)
            b, _ = self.lstm(y, prefix, True)
            y = np.concatenate((a, b), axis=1)

        _, a = self.lstm(y, "bidirectional_2", False)
        _, b = self.lstm(y, "bidirectional_2", True)
        y = (
            np.concatenate((a, b))
            @ self.weight("dense_5", "kernel")
            + self.weight("dense_5", "bias")
        )

        sbp = float(
            (
                y @ self.weight("SBP", "kernel")
                + self.weight("SBP", "bias")
            )[0]
        )
        dbp = float(
            (
                y @ self.weight("DBP", "kernel")
                + self.weight("DBP", "bias")
            )[0]
        )
        return sbp, dbp

    def predict(self, raw):
        resampled, filtered, normalized = self.preprocess_stages(raw)
        sbp, dbp = self.predict_normalized(normalized)
        if not (
            np.isfinite(sbp)
            and np.isfinite(dbp)
            and 60 <= sbp <= 240
            and 30 <= dbp <= 150
            and sbp > dbp
        ):
            raise ValueError("Salida fuera de rango; no se recorta")
        return sbp, dbp, resampled, filtered, normalized


def h5_array(path):
    with h5py.File(H5_PATH, "r") as f:
        return f["model_weights/" + path][:]


def build_tf_model():
    inp = tf.keras.Input(batch_shape=(1, 875, 1), dtype=tf.float32, name="input_4")
    x = tf.keras.layers.Conv1D(
        filters=32,
        kernel_size=5,
        strides=1,
        padding="causal",
        activation="relu",
        name="conv1d_48",
    )(inp)
    x = tf.keras.layers.Bidirectional(
        tf.keras.layers.LSTM(64, return_sequences=True),
        merge_mode="concat",
        name="bidirectional",
    )(x)
    x = tf.keras.layers.Bidirectional(
        tf.keras.layers.LSTM(64, return_sequences=True),
        merge_mode="concat",
        name="bidirectional_1",
    )(x)
    x = tf.keras.layers.Bidirectional(
        tf.keras.layers.LSTM(32, return_sequences=False),
        merge_mode="concat",
        name="bidirectional_2",
    )(x)
    x = tf.keras.layers.Dense(128, activation="linear", name="dense_5")(x)
    sbp = tf.keras.layers.Dense(1, activation="linear", name="SBP")(x)
    dbp = tf.keras.layers.Dense(1, activation="linear", name="DBP")(x)
    model = tf.keras.Model(inp, [sbp, dbp], name="LSTM")

    model.get_layer("conv1d_48").set_weights([
        h5_array("conv1d_48/conv1d_48/kernel:0"),
        h5_array("conv1d_48/conv1d_48/bias:0"),
    ])

    bi_specs = [
        (
            "bidirectional",
            "bidirectional/bidirectional/forward_lstm/lstm_cell_1",
            "bidirectional/bidirectional/backward_lstm/lstm_cell_2",
        ),
        (
            "bidirectional_1",
            "bidirectional_1/bidirectional_1/forward_lstm_1/lstm_cell_4",
            "bidirectional_1/bidirectional_1/backward_lstm_1/lstm_cell_5",
        ),
        (
            "bidirectional_2",
            "bidirectional_2/bidirectional_2/forward_lstm_2/lstm_cell_7",
            "bidirectional_2/bidirectional_2/backward_lstm_2/lstm_cell_8",
        ),
    ]
    for layer_name, forward, backward in bi_specs:
        layer = model.get_layer(layer_name)
        layer.forward_layer.set_weights([
            h5_array(forward + "/kernel:0"),
            h5_array(forward + "/recurrent_kernel:0"),
            h5_array(forward + "/bias:0"),
        ])
        layer.backward_layer.set_weights([
            h5_array(backward + "/kernel:0"),
            h5_array(backward + "/recurrent_kernel:0"),
            h5_array(backward + "/bias:0"),
        ])

    for name in ("dense_5", "SBP", "DBP"):
        model.get_layer(name).set_weights([
            h5_array(f"{name}/{name}/kernel:0"),
            h5_array(f"{name}/{name}/bias:0"),
        ])
    return model


def compare_tf_with_oracle(model, oracle, golden):
    rows = []
    for item in golden["windows"]:
        raw = item["raw"]
        sbp_o, dbp_o, _, _, normalized = oracle.predict(raw)
        tf_out = model(normalized.reshape(1, 875, 1), training=False)
        sbp_tf = float(np.asarray(tf_out[0]).reshape(-1)[0])
        dbp_tf = float(np.asarray(tf_out[1]).reshape(-1)[0])
        rows.append({
            "seq": item["seq"],
            "oracle_sbp": sbp_o,
            "oracle_dbp": dbp_o,
            "tf_sbp": sbp_tf,
            "tf_dbp": dbp_tf,
            "delta_sbp": sbp_tf - sbp_o,
            "delta_dbp": dbp_tf - dbp_o,
        })
        if abs(sbp_tf - sbp_o) >= 0.01 or abs(dbp_tf - dbp_o) >= 0.01:
            raise RuntimeError(f"TF model differs from oracle for seq {item['seq']}: {rows[-1]}")
    return rows


def convert_builtin_only(model):
    TFLITE_PATH.parent.mkdir(parents=True, exist_ok=True)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = []
    converter.target_spec.supported_types = [tf.float32]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.experimental_enable_resource_variables = True

    try:
        blob = converter.convert()
    except Exception as exc:
        raise RuntimeError(
            "TFLite conversion with TFLITE_BUILTINS only failed. "
            "SELECT_TF_OPS was intentionally NOT enabled."
        ) from exc

    TFLITE_PATH.write_bytes(blob)
    return blob


def invoke_tflite(normalized):
    interpreter = tf.lite.Interpreter(model_path=str(TFLITE_PATH))
    interpreter.allocate_tensors()

    input_detail = interpreter.get_input_details()
    output_detail = interpreter.get_output_details()
    if len(input_detail) != 1 or len(output_detail) != 2:
        raise RuntimeError("Unexpected TFLite IO count")

    x = normalized.reshape(1, 875, 1).astype(np.float32)
    interpreter.set_tensor(input_detail[0]["index"], x)
    interpreter.invoke()
    outputs = [
        float(np.asarray(interpreter.get_tensor(d["index"])).reshape(-1)[0])
        for d in output_detail
    ]
    return interpreter, input_detail, output_detail, outputs


def resolve_output_indices(oracle, golden):
    _, _, _, _, normalized = oracle.predict(golden["windows"][0]["raw"])
    interpreter, input_detail, output_detail, outputs = invoke_tflite(normalized)

    target = np.array(
        [
            oracle.predict(golden["windows"][0]["raw"])[0],
            oracle.predict(golden["windows"][0]["raw"])[1],
        ],
        dtype=float,
    )

    # Determine the output-index mapping by numerical identity instead of
    # depending on converter-generated tensor names.
    direct = abs(outputs[0]-target[0]) + abs(outputs[1]-target[1])
    swapped = abs(outputs[1]-target[0]) + abs(outputs[0]-target[1])
    if direct <= swapped:
        sbp_slot, dbp_slot = 0, 1
    else:
        sbp_slot, dbp_slot = 1, 0

    ops = interpreter._get_ops_details()
    op_names = [str(op["op_name"]) for op in ops]
    flex = [name for name in op_names if name.startswith("Flex") or "SELECT_TF" in name]
    if flex:
        raise RuntimeError(f"Unexpected Flex/SELECT_TF_OPS in converted model: {flex}")

    return {
        "sbp_output_slot": sbp_slot,
        "dbp_output_slot": dbp_slot,
        "input_details": [
            {
                "name": d["name"],
                "shape": [int(v) for v in d["shape"]],
                "dtype": str(d["dtype"]),
                "index": int(d["index"]),
            }
            for d in input_detail
        ],
        "output_details": [
            {
                "name": d["name"],
                "shape": [int(v) for v in d["shape"]],
                "dtype": str(d["dtype"]),
                "index": int(d["index"]),
            }
            for d in output_detail
        ],
        "ops": op_names,
    }


def validate_tflite(oracle, golden, mapping):
    rows = []
    for item in golden["windows"]:
        sbp_o, dbp_o, _, _, normalized = oracle.predict(item["raw"])
        _, _, _, outputs = invoke_tflite(normalized)
        sbp = outputs[mapping["sbp_output_slot"]]
        dbp = outputs[mapping["dbp_output_slot"]]

        row = {
            "seq": item["seq"],
            "oracle_sbp": sbp_o,
            "oracle_dbp": dbp_o,
            "tflite_sbp": sbp,
            "tflite_dbp": dbp,
            "delta_sbp": sbp - sbp_o,
            "delta_dbp": dbp - dbp_o,
            "historical_sbp": item["expected_sbp_historical"],
            "historical_dbp": item["expected_dbp_historical"],
            "delta_oracle_vs_historical_sbp": sbp_o - item["expected_sbp_historical"],
            "delta_oracle_vs_historical_dbp": dbp_o - item["expected_dbp_historical"],
        }
        rows.append(row)

        if abs(row["delta_sbp"]) >= 0.01 or abs(row["delta_dbp"]) >= 0.01:
            raise RuntimeError(f"TFLite differs from oracle for seq {item['seq']}: {row}")

        # The historical log is rounded to two decimals, so <= 0.01 is expected.
        if (
            abs(row["delta_oracle_vs_historical_sbp"]) >= 0.01
            or abs(row["delta_oracle_vs_historical_dbp"]) >= 0.01
        ):
            raise RuntimeError(f"Oracle differs from historical accepted output for seq {item['seq']}: {row}")

    return rows


def write_stage_goldens(oracle, golden):
    STAGES_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "generator": "tools/bp/convert_and_validate.py",
        "model_md5": MODEL_MD5,
        "sample_rate_hz": 100,
        "target_rate_hz": 125,
        "windows": [],
    }

    for item in golden["windows"]:
        sbp, dbp, resampled, filtered, normalized = oracle.predict(item["raw"])
        payload["windows"].append({
            "seq": item["seq"],
            "raw": item["raw"],
            "resampled": [float(v) for v in resampled],
            "filtered": [float(v) for v in filtered],
            "normalized": [float(v) for v in normalized],
            "oracle_sbp": sbp,
            "oracle_dbp": dbp,
        })

    STAGES_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def main():
    ensure_h5()
    architecture = inspect_and_assert_architecture()
    golden = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    oracle = Oracle()

    model = build_tf_model()
    tf_rows = compare_tf_with_oracle(model, oracle, golden)

    convert_builtin_only(model)
    mapping = resolve_output_indices(oracle, golden)
    tflite_rows = validate_tflite(oracle, golden, mapping)
    write_stage_goldens(oracle, golden)

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "canonical_h5_md5": MODEL_MD5,
        "tensorflow_version": tf.__version__,
        "architecture": architecture,
        "tflite_sha256": hashlib.sha256(TFLITE_PATH.read_bytes()).hexdigest(),
        "tflite_size_bytes": TFLITE_PATH.stat().st_size,
        "tflite_mapping": mapping,
        "tf_vs_oracle": tf_rows,
        "tflite_vs_oracle": tflite_rows,
        "max_abs_tf_delta_sbp": max(abs(r["delta_sbp"]) for r in tf_rows),
        "max_abs_tf_delta_dbp": max(abs(r["delta_dbp"]) for r in tf_rows),
        "max_abs_tflite_delta_sbp": max(abs(r["delta_sbp"]) for r in tflite_rows),
        "max_abs_tflite_delta_dbp": max(abs(r["delta_dbp"]) for r in tflite_rows),
        "max_abs_oracle_vs_historical_sbp": max(abs(r["delta_oracle_vs_historical_sbp"]) for r in tflite_rows),
        "max_abs_oracle_vs_historical_dbp": max(abs(r["delta_oracle_vs_historical_dbp"]) for r in tflite_rows),
        "select_tf_ops_used": False,
        "acceptance_mmHg": 0.01,
    }
    REPORT_PATH.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(json.dumps({
        "status": "PASS",
        "tflite": str(TFLITE_PATH),
        "report": str(REPORT_PATH),
        "max_abs_tflite_delta_sbp": report["max_abs_tflite_delta_sbp"],
        "max_abs_tflite_delta_dbp": report["max_abs_tflite_delta_dbp"],
        "select_tf_ops_used": False,
    }, indent=2))


if __name__ == "__main__":
    # Keep numerical behavior deterministic and avoid excessive CI threading.
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    np.seterr(divide="raise", over="raise", invalid="raise", under="ignore")
    main()
