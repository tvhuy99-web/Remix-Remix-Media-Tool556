package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable

data class StudioAudioDevice(
    val id: Int,
    val type: Int,
    val productName: String,
    val address: String,
    val isInput: Boolean,
    val isOutput: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
) {
    val label: String
        get() = productName.ifBlank { typeLabel(type) }

    val fingerprint: String
        get() = buildString {
            append(type)
            append('|')
            append(address.ifBlank { productName.trim().lowercase() })
        }

    companion object {
        fun typeLabel(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microphone máy"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loa máy"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Tai nghe có mic"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Tai nghe dây"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Speaker"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Audio device"
        }
    }
}

data class StudioAudioDeviceSnapshot(
    val inputs: List<StudioAudioDevice> = emptyList(),
    val outputs: List<StudioAudioDevice> = emptyList(),
)

class StudioAudioDeviceManager(
    context: Context,
    private val onChanged: (StudioAudioDeviceSnapshot) -> Unit,
) : Closeable {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = publish()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = publish()
    }

    init {
        audioManager?.registerAudioDeviceCallback(callback, handler)
        publish()
    }

    fun snapshot(): StudioAudioDeviceSnapshot {
        val manager = audioManager ?: return StudioAudioDeviceSnapshot()
        return StudioAudioDeviceSnapshot(
            inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::toModel).sortedWith(deviceComparator()),
            outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::toModel).sortedWith(deviceComparator()),
        )
    }

    fun find(id: Int?, input: Boolean): StudioAudioDevice? {
        if (id == null) return null
        val devices = if (input) snapshot().inputs else snapshot().outputs
        return devices.firstOrNull { it.id == id }
    }

    override fun close() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    private fun publish() {
        onChanged(snapshot())
    }

    private fun toModel(info: AudioDeviceInfo): StudioAudioDevice = StudioAudioDevice(
        id = info.id,
        type = info.type,
        productName = info.productName?.toString().orEmpty(),
        address = info.address.orEmpty(),
        isInput = info.isSource,
        isOutput = info.isSink,
        sampleRates = info.sampleRates.filter { it > 0 }.distinct().sorted(),
        channelCounts = info.channelCounts.filter { it > 0 }.distinct().sorted(),
    )

    private fun deviceComparator(): Comparator<StudioAudioDevice> = compareBy<StudioAudioDevice>(
        { devicePriority(it.type) },
        { it.label.lowercase() },
        { it.id },
    )

    private fun devicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 1
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 3
        else -> 4
    }
}
