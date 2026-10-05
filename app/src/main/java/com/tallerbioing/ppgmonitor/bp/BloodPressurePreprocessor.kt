package com.tallerbioing.ppgmonitor.bp

import kotlin.math.sqrt

/**
 * Exact Android-side preprocessing contract for the canonical BP model.
 *
 * Reference chain:
 * 700 raw MAX30102 IR samples @ 100 Hz
 * -> scipy.signal.resample_poly(x, 5, 4), default Kaiser(5.0)
 * -> 875 samples @ 125 Hz
 * -> scipy.signal.butter(4, [0.5, 8], bandpass, fs=125)
 * -> scipy.signal.filtfilt(...), default odd padding, padlen=27
 * -> per-window z-score with population standard deviation (ddof=0)
 * -> Float32[875].
 *
 * The constants below are frozen from SciPy 1.14.1, which is also pinned by
 * tools/bp/convert_and_validate.py. They are not a new filter design.
 */
object BloodPressurePreprocessor {

    const val RAW_SAMPLE_COUNT = 700
    const val MODEL_SAMPLE_COUNT = 875
    const val RAW_SAMPLE_RATE_HZ = 100
    const val MODEL_SAMPLE_RATE_HZ = 125

    private const val RAW_MIN_ALLOWED = 8000.0
    private const val RAW_MAX_EXCLUSIVE = 260000.0
    private const val MIN_FILTERED_STD = 1e-6

    private const val RESAMPLE_UP = 5
    private const val RESAMPLE_DOWN = 4
    private const val RESAMPLE_PRE_REMOVE = 13
    private const val FILTFILT_EDGE = 27

    data class Result(
        val resampled: DoubleArray,
        val filtered: DoubleArray,
        val normalized: FloatArray
    )

    fun preprocess(rawIr: LongArray): Result =
        preprocess(DoubleArray(rawIr.size) { rawIr[it].toDouble() })

    fun preprocess(rawIr: DoubleArray): Result {
        require(rawIr.size == RAW_SAMPLE_COUNT) {
            "Ventana incompleta: se requieren exactamente $RAW_SAMPLE_COUNT muestras IR"
        }

        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (value in rawIr) {
            require(value.isFinite()) { "Ventana no finita" }
            if (value < min) min = value
            if (value > max) max = value
        }

        require(min >= RAW_MIN_ALLOWED && max < RAW_MAX_EXCLUSIVE) {
            "Sin contacto o cerca de saturación"
        }

        val resampled = resamplePoly5Over4(rawIr)
        val filtered = filtfiltButterworth(resampled)

        var mean = 0.0
        for (value in filtered) mean += value
        mean /= filtered.size.toDouble()

        var sumSquares = 0.0
        for (value in filtered) {
            val delta = value - mean
            sumSquares += delta * delta
        }
        val std = sqrt(sumSquares / filtered.size.toDouble())

        require(std.isFinite() && std >= MIN_FILTERED_STD) {
            "Onda constante"
        }

        val normalized = FloatArray(filtered.size)
        for (i in filtered.indices) {
            val value = ((filtered[i] - mean) / std).toFloat()
            require(value.isFinite()) { "Normalización no finita" }
            normalized[i] = value
        }

        return Result(
            resampled = resampled,
            filtered = filtered,
            normalized = normalized
        )
    }

    /**
     * Direct convolution equivalent of scipy.signal.resample_poly(x, 5, 4)
     * for this fixed 700-sample contract.
     *
     * SciPy upsamples by 5, applies the frozen FIR below, downsamples by 4,
     * then removes the first 13 output samples to center the zero-phase FIR.
     * Computing only non-zero upsampled positions avoids materializing the
     * 3500-sample zero-inserted vector.
     */
    private fun resamplePoly5Over4(input: DoubleArray): DoubleArray {
        val output = DoubleArray(MODEL_SAMPLE_COUNT)

        for (outIndex in output.indices) {
            val convolutionIndex =
                (outIndex + RESAMPLE_PRE_REMOVE) * RESAMPLE_DOWN

            var sum = 0.0
            for (filterIndex in RESAMPLE_FIR.indices) {
                val upsampledIndex = convolutionIndex - filterIndex
                if (upsampledIndex < 0) continue
                if (upsampledIndex % RESAMPLE_UP != 0) continue

                val inputIndex = upsampledIndex / RESAMPLE_UP
                if (inputIndex >= input.size) continue

                sum += RESAMPLE_FIR[filterIndex] * input[inputIndex]
            }
            output[outIndex] = sum
        }

        return output
    }

    /**
     * Equivalent of SciPy filtfilt(b, a, x) for the frozen 9-tap IIR:
     * odd extension of 27 samples, steady-state zi initialization, forward
     * Direct Form II transposed filtering, reverse filtering and crop.
     */
    private fun filtfiltButterworth(input: DoubleArray): DoubleArray {
        require(input.size > FILTFILT_EDGE)

        val extended = DoubleArray(input.size + 2 * FILTFILT_EDGE)
        val first = input.first()
        val last = input.last()

        for (i in 0 until FILTFILT_EDGE) {
            extended[i] =
                2.0 * first - input[FILTFILT_EDGE - i]
        }

        input.copyInto(
            destination = extended,
            destinationOffset = FILTFILT_EDGE
        )

        for (i in 0 until FILTFILT_EDGE) {
            extended[FILTFILT_EDGE + input.size + i] =
                2.0 * last - input[input.size - 2 - i]
        }

        val forwardInitial = DoubleArray(FILTER_ZI.size) {
            FILTER_ZI[it] * extended[0]
        }
        val forward = lfilter(extended, forwardInitial)

        forward.reverse()

        val backwardInitial = DoubleArray(FILTER_ZI.size) {
            FILTER_ZI[it] * forward[0]
        }
        val backward = lfilter(forward, backwardInitial)

        backward.reverse()

        return backward.copyOfRange(
            FILTFILT_EDGE,
            backward.size - FILTFILT_EDGE
        )
    }

    /**
     * scipy.signal.lfilter direct-form-II-transposed recurrence.
     * A[0] is 1.0 in the frozen Butterworth coefficients.
     */
    private fun lfilter(
        input: DoubleArray,
        initialState: DoubleArray
    ): DoubleArray {
        val state = initialState.copyOf()
        val output = DoubleArray(input.size)

        for (sampleIndex in input.indices) {
            val x = input[sampleIndex]
            val y = FILTER_B[0] * x + state[0]
            output[sampleIndex] = y

            for (stateIndex in 0 until state.lastIndex) {
                state[stateIndex] =
                    state[stateIndex + 1] +
                    FILTER_B[stateIndex + 1] * x -
                    FILTER_A[stateIndex + 1] * y
            }

            state[state.lastIndex] =
                FILTER_B.last() * x -
                FILTER_A.last() * y
        }

        return output
    }

    private val FILTER_B = doubleArrayOf(
        0.00080635986503710226,
        0.0,
        -0.003225439460148409,
        0.0,
        0.0048381591902226136,
        0.0,
        -0.003225439460148409,
        0.0,
        0.00080635986503710226
    )

    private val FILTER_A = doubleArrayOf(
        1.0,
        -6.9816981082262926,
        21.388231113401652,
        -37.565347331741847,
        41.385017534431171,
        -29.290586496053383,
        13.007357681894121,
        -3.3137886029935149,
        0.37081421592945468
    )

    private val FILTER_ZI = doubleArrayOf(
        -0.00080635945756311639,
        -0.00080636230242347182,
        0.0024190858728727186,
        0.0024190705659709148,
        -0.0024190717609336543,
        -0.0024190836960856811,
        0.00080636106422260598,
        0.00080635971393995564
    )

    /**
     * SciPy 1.14.1:
     * firwin(101, 0.2, window=("kaiser", 5.0)) * 5,
     * prefixed with two zeros by resample_poly's centering logic.
     */
    private val RESAMPLE_FIR = doubleArrayOf(
        0.0,
        0.0,
        -1.4319845861218768e-18,
        -0.00088594463307774046,
        -0.001799469602957757,
        -0.002213513208519115,
        -0.0016558759340072661,
        3.7090928077630901e-18,
        0.0023369416631838173,
        0.0044255972164973043,
        0.0051378945714475495,
        0.003660422624556515,
        -6.9892197510166465e-18,
        -0.0047768657934134113,
        -0.0087611431147934238,
        -0.0098864481718803632,
        -0.0068670260525744934,
        1.1214393384808607e-17,
        0.0085812284098957161,
        0.015448261067018673,
        0.01714039095900461,
        0.011724211241501204,
        -1.6184178029011696e-17,
        -0.014266651185604516,
        -0.025391595484867625,
        -0.027884803844175424,
        -0.018899291546895902,
        2.1565131317676137e-17,
        0.022651951471119487,
        0.040075197081300362,
        0.043795030769745211,
        0.029570563158240915,
        -2.6922934505517284e-17,
        -0.035301668276477453,
        -0.062454331837739116,
        -0.068352674559907248,
        -0.04629732232860892,
        3.1773425944513475e-17,
        0.055950317348399642,
        0.099954085010796348,
        0.11079961032593512,
        0.076291532042500623,
        -3.564515353744021e-17,
        -0.096700041092650763,
        -0.17875061904443101,
        -0.20707014314806291,
        -0.15106305431085065,
        3.8143665239319465e-17,
        0.23069847278311872,
        0.50083303222230358,
        0.75461664312424637,
        0.93526184178996541,
        1.0006505165814363,
        0.93526184178996541,
        0.75461664312424637,
        0.50083303222230358,
        0.23069847278311872,
        3.8143665239319465e-17,
        -0.15106305431085065,
        -0.20707014314806291,
        -0.17875061904443101,
        -0.096700041092650763,
        -3.564515353744021e-17,
        0.076291532042500623,
        0.11079961032593512,
        0.099954085010796348,
        0.055950317348399642,
        3.1773425944513475e-17,
        -0.04629732232860892,
        -0.068352674559907248,
        -0.062454331837739116,
        -0.035301668276477453,
        -2.6922934505517284e-17,
        0.029570563158240915,
        0.043795030769745211,
        0.040075197081300362,
        0.022651951471119487,
        2.1565131317676137e-17,
        -0.018899291546895902,
        -0.027884803844175424,
        -0.025391595484867625,
        -0.014266651185604516,
        -1.6184178029011696e-17,
        0.011724211241501204,
        0.01714039095900461,
        0.015448261067018673,
        0.0085812284098957161,
        1.1214393384808607e-17,
        -0.0068670260525744934,
        -0.0098864481718803632,
        -0.0087611431147934238,
        -0.0047768657934134113,
        -6.9892197510166465e-18,
        0.003660422624556515,
        0.0051378945714475495,
        0.0044255972164973043,
        0.0023369416631838173,
        3.7090928077630901e-18,
        -0.0016558759340072661,
        -0.002213513208519115,
        -0.001799469602957757,
        -0.00088594463307774046,
        -1.4319845861218768e-18
    )
}
