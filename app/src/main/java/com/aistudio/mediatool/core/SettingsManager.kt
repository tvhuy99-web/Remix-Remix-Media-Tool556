package com.aistudio.mediatool.core

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_VID_Q_INDEX = "vid_q_index"
    private const val KEY_AUD_B_INDEX = "aud_b_index"
    private const val KEY_AUD_FMT_INDEX = "aud_fmt_index"
    private const val KEY_FADE_DURATION = "fade_duration_sec"
    private const val KEY_HW_ACCEL_INDEX = "hw_accel_index"
    private const val KEY_NUM_THREADS_INDEX = "num_threads_index"
    private const val KEY_STEM_MODE_INDEX = "stem_mode_index"
    private const val KEY_STEM_MODEL_TWO = "stem_model_two"
    private const val KEY_STEM_MODEL_FOUR = "stem_model_four"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getHardwareAccelIndex(context: Context): Int = prefs(context).getInt(KEY_HW_ACCEL_INDEX, 0).coerceIn(0, 2)
    fun setHardwareAccelIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_HW_ACCEL_INDEX, value.coerceIn(0, 2)).apply()

    fun getNumThreadsIndex(context: Context): Int = prefs(context).getInt(KEY_NUM_THREADS_INDEX, 2).coerceIn(0, 3)
    fun setNumThreadsIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_NUM_THREADS_INDEX, value.coerceIn(0, 3)).apply()
    fun getNumThreads(context: Context): Int = intArrayOf(1, 2, 4, 8)[getNumThreadsIndex(context)]

    fun getStemModeIndex(context: Context): Int = prefs(context).getInt(KEY_STEM_MODE_INDEX, 0).coerceIn(0, 1)
    fun setStemModeIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_STEM_MODE_INDEX, value.coerceIn(0, 1)).apply()

    fun getStemModelId(context: Context, stemModeIndex: Int): String? =
        prefs(context).getString(if (stemModeIndex == 1) KEY_STEM_MODEL_FOUR else KEY_STEM_MODEL_TWO, null)

    fun setStemModelId(context: Context, stemModeIndex: Int, modelId: String) =
        prefs(context).edit()
            .putString(if (stemModeIndex == 1) KEY_STEM_MODEL_FOUR else KEY_STEM_MODEL_TWO, modelId)
            .apply()

    fun getFadeDurationSec(context: Context): Int = prefs(context).getInt(KEY_FADE_DURATION, 3).coerceIn(0, 10)
    fun setFadeDurationSec(context: Context, value: Int) = prefs(context).edit().putInt(KEY_FADE_DURATION, value.coerceIn(0, 10)).apply()

    fun getVidQualityIndex(context: Context): Int = prefs(context).getInt(KEY_VID_Q_INDEX, 1).coerceIn(0, 4)
    fun setVidQualityIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_VID_Q_INDEX, value.coerceIn(0, 4)).apply()
    fun getVideoBitrateArg(context: Context): String = arrayOf("-b:v 2M", "-b:v 5M", "-b:v 10M", "-b:v 20M", "-b:v 50M")[getVidQualityIndex(context)]
    fun getVideoPresetArg(context: Context): String = arrayOf("-preset ultrafast", "-preset medium", "-preset slow", "-preset slower", "-preset veryslow")[getVidQualityIndex(context)]

    fun getAudBitrateIndex(context: Context): Int = prefs(context).getInt(KEY_AUD_B_INDEX, 3).coerceIn(0, 4)
    fun setAudBitrateIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_AUD_B_INDEX, value.coerceIn(0, 4)).apply()
    fun getAudioBitrateInt(context: Context): Int = intArrayOf(128_000, 192_000, 256_000, 320_000, 320_000)[getAudBitrateIndex(context)]

    fun getAudFormatIndex(context: Context): Int = prefs(context).getInt(KEY_AUD_FMT_INDEX, 0).coerceIn(0, 3)
    fun setAudFormatIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_AUD_FMT_INDEX, value.coerceIn(0, 3)).apply()

    /**
     * "Lossless" không thể đạt được bằng `-c:a copy` khi lệnh có filter, trộn hoặc đổi
     * container. Khi người dùng chọn lossless với AAC/MP3, ứng dụng chuyển sang FLAC.
     */
    fun getAudioFormatExt(context: Context): String {
        val selected = arrayOf("m4a", "mp3", "wav", "flac")[getAudFormatIndex(context)]
        return if (getAudBitrateIndex(context) == 4 && selected in setOf("m4a", "mp3")) "flac" else selected
    }

    fun getAudioCodecArg(context: Context): String = when (getAudioFormatExt(context)) {
        "m4a" -> "-c:a aac"
        "mp3" -> "-c:a libmp3lame"
        "wav" -> "-c:a pcm_s16le"
        "flac" -> "-c:a flac"
        else -> "-c:a aac"
    }

    fun getAudioBitrateArg(context: Context): String = when (getAudioFormatExt(context)) {
        "wav", "flac" -> ""
        else -> "-b:a ${getAudioBitrateInt(context) / 1000}k"
    }

    fun getAudioEncodingArgs(context: Context): String =
        listOf(getAudioCodecArg(context), getAudioBitrateArg(context))
            .filter { it.isNotBlank() }
            .joinToString(" ")

    fun isAudioLossless(context: Context): Boolean = getAudioFormatExt(context) in setOf("wav", "flac")
}
