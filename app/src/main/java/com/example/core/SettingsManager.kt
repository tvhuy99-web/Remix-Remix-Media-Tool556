package com.example.core

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "app_settings"

    // Key constants
    private const val KEY_VID_Q_INDEX = "vid_q_index"
    private const val KEY_AUD_B_INDEX = "aud_b_index"
    private const val KEY_AUD_FMT_INDEX = "aud_fmt_index"
    private const val KEY_FADE_DURATION = "fade_duration_sec"
    private const val KEY_HW_ACCEL_INDEX = "hw_accel_index"
    private const val KEY_NUM_THREADS_INDEX = "num_threads_index"
    private const val KEY_STEM_MODE_INDEX = "stem_mode_index"
    private const val KEY_PIPELINE_MODE = "pipeline_mode_index"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Pipeline mode (0 = Sequential, 1 = Pipeline/Channel)
    fun getPipelineModeIndex(context: Context): Int = getPrefs(context).getInt(KEY_PIPELINE_MODE, 0)
    fun setPipelineModeIndex(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_PIPELINE_MODE, value).apply()

    // Tăng tốc phần cứng (0 = Tắt, 1 = NNAPI, 2 = XNNPACK)
    fun getHardwareAccelIndex(context: Context): Int = getPrefs(context).getInt(KEY_HW_ACCEL_INDEX, 0)
    fun setHardwareAccelIndex(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_HW_ACCEL_INDEX, value).apply()

    // Số luồng CPU / Tận dụng RAM
    fun getNumThreadsIndex(context: Context): Int = getPrefs(context).getInt(KEY_NUM_THREADS_INDEX, 2)
    fun setNumThreadsIndex(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_NUM_THREADS_INDEX, value).apply()

    fun getNumThreads(context: Context): Int {
        return when (getNumThreadsIndex(context)) {
            0 -> 1
            1 -> 2
            2 -> 4
            3 -> 8
            else -> 4
        }
    }

    // 0 = 2 Stems (Vocals, Beat), 1 = 4 Stems (Vocals, Drums, Bass, Other)
    fun getStemModeIndex(context: Context): Int = getPrefs(context).getInt(KEY_STEM_MODE_INDEX, 0)
    fun setStemModeIndex(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_STEM_MODE_INDEX, value).apply()

    // Fade Duration Index (0 = Off, 1..10 seconds)
    fun getFadeDurationSec(context: Context): Int = getPrefs(context).getInt(KEY_FADE_DURATION, 3)
    fun setFadeDurationSec(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_FADE_DURATION, value).apply()

    // Video Quality Index (Default 1 mapping to 5 Mbps // Medium)
    fun getVidQualityIndex(context: Context): Int = getPrefs(context).getInt(KEY_VID_Q_INDEX, 1)
    fun setVidQualityIndex(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_VID_Q_INDEX, value).apply()

    fun getVideoBitrateArg(context: Context): String {
        return when (getVidQualityIndex(context)) {
            0 -> "-b:v 2M"
            1 -> "-b:v 5M"
            2 -> "-b:v 10M"
            3 -> "-b:v 20M"
            4 -> "-b:v 50M"
            else -> "-b:v 5M"
        }
    }

    fun getVideoPresetArg(context: Context): String {
        // Tie preset to the quality selection loosely
        return when (getVidQualityIndex(context)) {
            0 -> "-preset ultrafast" // Low quality uses ultrafast
            1 -> "-preset medium" // Medium
            2 -> "-preset slow"
            3 -> "-preset slower"
            4 -> "-preset veryslow"
            else -> "-preset medium"
        }
    }

    // Audio Bitrate Index (Default 3 mapping to 320k)
    fun getAudBitrateIndex(context: Context): Int = getPrefs(context).getInt(KEY_AUD_B_INDEX, 3)
    fun setAudBitrateIndex(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_AUD_B_INDEX, value).apply()

    fun getAudioBitrateArg(context: Context): String {
        return when (getAudBitrateIndex(context)) {
            0 -> "-b:a 128k"
            1 -> "-b:a 192k"
            2 -> "-b:a 256k"
            3 -> "-b:a 320k"
            4 -> "-c:a copy" // Lossless - depends on situation but we return flag
            else -> "-b:a 320k"
        }
    }
    
    fun getAudioBitrateInt(context: Context): Int {
        return when (getAudBitrateIndex(context)) {
            0 -> 128_000
            1 -> 192_000
            2 -> 256_000
            3 -> 320_000
            4 -> 320_000 // Lossless ko được support ở MediaRecorder gốc nên xài max bitrate
            else -> 192_000
        }
    }

    // To check if audio is set to copy
    fun isAudioLossless(context: Context): Boolean = getAudBitrateIndex(context) == 4

    // Audio Format Index (Default 0 mapping to AAC/.m4a)
    fun getAudFormatIndex(context: Context): Int = getPrefs(context).getInt(KEY_AUD_FMT_INDEX, 0)
    fun setAudFormatIndex(context: Context, value: Int) = getPrefs(context).edit().putInt(KEY_AUD_FMT_INDEX, value).apply()

    fun getAudioFormatExt(context: Context): String {
        return when (getAudFormatIndex(context)) {
            0 -> "m4a"
            1 -> "mp3"
            2 -> "wav"
            3 -> "flac"
            else -> "m4a"
        }
    }
    
    fun getAudioCodecArg(context: Context): String {
        return when (getAudFormatIndex(context)) {
            0 -> "-c:a aac"
            1 -> "-c:a libmp3lame"
            2 -> "-c:a pcm_s16le"
            3 -> "-c:a flac"
            else -> "-c:a aac"
        }
    }
}
