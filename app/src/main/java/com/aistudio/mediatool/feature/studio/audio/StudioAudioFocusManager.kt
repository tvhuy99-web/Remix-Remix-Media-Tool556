package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Small audio-focus owner for the native Studio engine.
 *
 * Studio intentionally pauses rather than ducks because a gain change while recording
 * or judging a mix is more surprising than a clean interruption. Focus gain never
 * auto-resumes transport; the user decides when to continue.
 */
class StudioAudioFocusManager(
    context: Context,
    private val onFocusLost: () -> Unit,
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusLost()
        }
    }

    private var activeRequest: AudioFocusRequest? = null
    private var legacyActive = false
    private var activeGain: Int? = null

    fun requestPlayback(): Boolean = request(AudioManager.AUDIOFOCUS_GAIN)

    fun requestRecording(): Boolean = request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)

    @Suppress("DEPRECATION")
    fun abandon() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeRequest?.let(manager::abandonAudioFocusRequest)
            activeRequest = null
        } else if (legacyActive) {
            manager.abandonAudioFocus(listener)
        }
        legacyActive = false
        activeGain = null
    }

    private fun request(gain: Int, restorePreviousOnFailure: Boolean = true): Boolean {
        val previousGain = activeGain
        abandon()
        val granted = requestFresh(gain)
        if (granted) return true
        if (restorePreviousOnFailure && previousGain != null && previousGain != gain) {
            request(gain = previousGain, restorePreviousOnFailure = false)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun requestFresh(gain: Int): Boolean {
        val manager = audioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(true)
                .build()
            manager.requestAudioFocus(request).also { focusResult ->
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) activeRequest = request
            }
        } else {
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain).also { focusResult ->
                legacyActive = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) activeGain = gain
        return granted
    }
}
