package com.tallerbioing.ppgmonitor.bp

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BloodPressureEstimate(
    val systolicMmHg: Float,
    val diastolicMmHg: Float
)

/**
 * Thin Android wrapper around the canonical float32 TFLite model.
 *
 * The network receives only the already-preprocessed Float32[875] signal.
 * All signal processing remains in [BloodPressurePreprocessor].
 *
 * Output slot mapping was established by numerical validation against the
 * Python oracle:
 * - TFLite output slot 0 = DBP
 * - TFLite output slot 1 = SBP
 */
class BloodPressureModel private constructor(
    private val interpreter: Interpreter
) : Closeable {

    companion object {
        const val MODEL_ASSET = "lstm_ppg_nonmixed.tflite"

        private const val DBP_OUTPUT_SLOT = 0
        private const val SBP_OUTPUT_SLOT = 1

        private const val MIN_SBP = 60.0f
        private const val MAX_SBP = 240.0f
        private const val MIN_DBP = 30.0f
        private const val MAX_DBP = 150.0f

        fun fromAssets(
            context: Context,
            numThreads: Int = 2
        ): BloodPressureModel {
            val bytes =
                context.assets
                    .open(MODEL_ASSET)
                    .use { it.readBytes() }

            val modelBuffer =
                ByteBuffer
                    .allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        put(bytes)
                        rewind()
                    }

            val options =
                Interpreter.Options()
                    .setNumThreads(numThreads)

            val interpreter =
                Interpreter(
                    modelBuffer,
                    options
                )

            validateTensorContract(interpreter)

            return BloodPressureModel(interpreter)
        }

        internal fun validateEstimate(
            systolic: Float,
            diastolic: Float
        ): BloodPressureEstimate {
            require(systolic.isFinite() && diastolic.isFinite()) {
                "Salida PA no finita"
            }

            require(systolic in MIN_SBP..MAX_SBP) {
                "PAS fuera de rango"
            }

            require(diastolic in MIN_DBP..MAX_DBP) {
                "PAD fuera de rango"
            }

            require(systolic > diastolic) {
                "PAS debe ser mayor que PAD"
            }

            // Deliberately no clipping: this reproduces modelo_pa.py.
            return BloodPressureEstimate(
                systolicMmHg = systolic,
                diastolicMmHg = diastolic
            )
        }

        private fun validateTensorContract(
            interpreter: Interpreter
        ) {
            require(interpreter.inputTensorCount == 1) {
                "Modelo PA incompatible: se esperaba una entrada"
            }

            require(interpreter.outputTensorCount == 2) {
                "Modelo PA incompatible: se esperaban dos salidas"
            }

            val input = interpreter.getInputTensor(0)
            require(input.dataType() == DataType.FLOAT32) {
                "Modelo PA incompatible: entrada no float32"
            }

            require(
                input.shape().contentEquals(
                    intArrayOf(
                        1,
                        BloodPressurePreprocessor.MODEL_SAMPLE_COUNT,
                        1
                    )
                )
            ) {
                "Modelo PA incompatible: forma de entrada inesperada"
            }

            for (slot in 0..1) {
                val output = interpreter.getOutputTensor(slot)
                require(output.dataType() == DataType.FLOAT32) {
                    "Modelo PA incompatible: salida no float32"
                }
                require(
                    output.shape().contentEquals(
                        intArrayOf(1, 1)
                    )
                ) {
                    "Modelo PA incompatible: forma de salida inesperada"
                }
            }
        }
    }

    fun predict(
        normalized: FloatArray
    ): BloodPressureEstimate {
        require(
            normalized.size ==
                BloodPressurePreprocessor.MODEL_SAMPLE_COUNT
        ) {
            "El modelo PA requiere exactamente " +
                "${BloodPressurePreprocessor.MODEL_SAMPLE_COUNT} muestras"
        }

        for (value in normalized) {
            require(value.isFinite()) {
                "Entrada normalizada no finita"
            }
        }

        val input =
            ByteBuffer
                .allocateDirect(
                    normalized.size *
                        Float.SIZE_BYTES
                )
                .order(ByteOrder.nativeOrder())

        for (value in normalized) {
            input.putFloat(value)
        }
        input.rewind()

        val dbpOutput =
            Array(1) {
                FloatArray(1)
            }

        val sbpOutput =
            Array(1) {
                FloatArray(1)
            }

        val outputs =
            hashMapOf<Int, Any>(
                DBP_OUTPUT_SLOT to dbpOutput,
                SBP_OUTPUT_SLOT to sbpOutput
            )

        interpreter.runForMultipleInputsOutputs(
            arrayOf(input),
            outputs
        )

        return validateEstimate(
            systolic = sbpOutput[0][0],
            diastolic = dbpOutput[0][0]
        )
    }

    fun predictRaw(
        rawIr: LongArray
    ): BloodPressureEstimate =
        predict(
            BloodPressurePreprocessor
                .preprocess(rawIr)
                .normalized
        )

    override fun close() {
        interpreter.close()
    }
}
