package com.aistudio.mediatool.core.spatial

import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal object PcmStereoAnalyzer {
    private const val BYTES_PER_FRAME = 8
    private const val BUFFER_BYTES = 64 * 1024
    private const val DB_FLOOR = -160f

    fun analyze(file: File): PcmStereoMetrics {
        require(file.isFile && file.length() >= BYTES_PER_FRAME) {
            "PCM stereo f32le không hợp lệ: ${file.absolutePath}"
        }

        var frames = 0L
        var finiteFrames = 0L
        var nonFiniteSamples = 0L
        var clippedSamples = 0L
        var peakLeft = 0.0
        var peakRight = 0.0
        var sumSquaresLeft = 0.0
        var sumSquaresRight = 0.0
        var sumProducts = 0.0
        var sumDifferenceSquares = 0.0

        BufferedInputStream(file.inputStream(), BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES + BYTES_PER_FRAME)
            var carry = 0
            while (true) {
                val read = input.read(buffer, carry, BUFFER_BYTES - carry)
                if (read < 0) break
                val available = carry + read
                val processBytes = available - (available % BYTES_PER_FRAME)
                val values = ByteBuffer.wrap(buffer, 0, processBytes).order(ByteOrder.LITTLE_ENDIAN)

                while (values.remaining() >= BYTES_PER_FRAME) {
                    val left = values.float.toDouble()
                    val right = values.float.toDouble()
                    frames++

                    if (!left.isFinite()) nonFiniteSamples++
                    if (!right.isFinite()) nonFiniteSamples++
                    if (!left.isFinite() || !right.isFinite()) continue

                    finiteFrames++
                    val absLeft = abs(left)
                    val absRight = abs(right)
                    peakLeft = max(peakLeft, absLeft)
                    peakRight = max(peakRight, absRight)
                    if (absLeft > 1.0) clippedSamples++
                    if (absRight > 1.0) clippedSamples++
                    sumSquaresLeft += left * left
                    sumSquaresRight += right * right
                    sumProducts += left * right
                    val difference = left - right
                    sumDifferenceSquares += difference * difference
                }

                carry = available - processBytes
                if (carry > 0) {
                    buffer.copyInto(buffer, destinationOffset = 0, startIndex = processBytes, endIndex = available)
                }
            }
        }

        if (finiteFrames == 0L) {
            return PcmStereoMetrics(
                frames = frames,
                finiteFrames = 0,
                nonFiniteSamples = nonFiniteSamples,
                clippedSamples = clippedSamples,
            )
        }

        val divisor = finiteFrames.toDouble()
        val rmsLeft = sqrt(sumSquaresLeft / divisor)
        val rmsRight = sqrt(sumSquaresRight / divisor)
        val rms = sqrt((sumSquaresLeft + sumSquaresRight) / (2.0 * divisor))
        val differenceRms = sqrt(sumDifferenceSquares / divisor)
        val correlationDenominator = sqrt(sumSquaresLeft * sumSquaresRight)
        val correlation = if (correlationDenominator > 1e-20) {
            (sumProducts / correlationDenominator).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
        val balanceDb = amplitudeDb(rmsRight) - amplitudeDb(rmsLeft)
        val relativeDifference = differenceRms / max(rms, 1e-12)
        val dualMono = correlation >= 0.9995 && abs(balanceDb) <= 0.1 && relativeDifference <= 0.01

        return PcmStereoMetrics(
            frames = frames,
            finiteFrames = finiteFrames,
            nonFiniteSamples = nonFiniteSamples,
            clippedSamples = clippedSamples,
            peak = max(peakLeft, peakRight).toFloat(),
            peakLeft = peakLeft.toFloat(),
            peakRight = peakRight.toFloat(),
            rmsDbfs = amplitudeDb(rms),
            rmsLeftDbfs = amplitudeDb(rmsLeft),
            rmsRightDbfs = amplitudeDb(rmsRight),
            correlation = correlation.toFloat(),
            balanceDb = balanceDb,
            differenceRmsDbfs = amplitudeDb(differenceRms),
            dualMono = dualMono,
        )
    }

    private fun amplitudeDb(value: Double): Float = if (value <= 1e-8 || !value.isFinite()) {
        DB_FLOOR
    } else {
        (20.0 * log10(value)).toFloat().coerceAtLeast(DB_FLOOR)
    }
}

internal data class PcmStereoMetrics(
    val frames: Long = 0L,
    val finiteFrames: Long = 0L,
    val nonFiniteSamples: Long = 0L,
    val clippedSamples: Long = 0L,
    val peak: Float = 0f,
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
    val rmsDbfs: Float = -160f,
    val rmsLeftDbfs: Float = -160f,
    val rmsRightDbfs: Float = -160f,
    val correlation: Float = 0f,
    val balanceDb: Float = 0f,
    val differenceRmsDbfs: Float = -160f,
    val dualMono: Boolean = false,
) {
    fun diagnosticFields(prefix: String): Map<String, Any?> = mapOf(
        "${prefix}_frames" to frames,
        "${prefix}_finite_frames" to finiteFrames,
        "${prefix}_nonfinite_samples" to nonFiniteSamples,
        "${prefix}_clipped_samples" to clippedSamples,
        "${prefix}_peak" to peak,
        "${prefix}_peak_left" to peakLeft,
        "${prefix}_peak_right" to peakRight,
        "${prefix}_rms_dbfs" to rmsDbfs,
        "${prefix}_rms_left_dbfs" to rmsLeftDbfs,
        "${prefix}_rms_right_dbfs" to rmsRightDbfs,
        "${prefix}_correlation" to correlation,
        "${prefix}_balance_db" to balanceDb,
        "${prefix}_difference_rms_dbfs" to differenceRmsDbfs,
        "${prefix}_dual_mono" to dualMono,
    )
}
