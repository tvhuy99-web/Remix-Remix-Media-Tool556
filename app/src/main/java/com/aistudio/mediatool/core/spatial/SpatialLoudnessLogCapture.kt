package com.aistudio.mediatool.core.spatial

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Captures loudnorm JSON in release builds without exposing general FFmpeg logs to callers. */
internal object SpatialLoudnessLogCapture {
    suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        val session = FFmpegKit.execute(command)
        val returnCode = session.returnCode
        check(ReturnCode.isSuccess(returnCode)) {
            session.failStackTrace?.takeIf(String::isNotBlank)
                ?: "FFmpeg loudness analysis failed: $returnCode"
        }
        buildString {
            append(session.getAllLogsAsString(10_000).orEmpty())
            append('\n')
            append(session.output.orEmpty())
        }.also { logs ->
            check(logs.contains("\"input_i\"")) {
                "FFmpeg loudness analysis returned no loudnorm JSON"
            }
        }
    }
}
