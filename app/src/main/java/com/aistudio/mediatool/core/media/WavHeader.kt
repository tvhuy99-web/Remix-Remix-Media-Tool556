package com.aistudio.mediatool.core.media

object WavHeader {
    const val HEADER_SIZE = 44
    const val MAX_PCM_BYTES = 0xffff_ffffL - 36L

    fun create(
        pcmBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        require(pcmBytes in 0..MAX_PCM_BYTES) { "WAV cổ điển chỉ hỗ trợ tối đa khoảng 4 GB dữ liệu PCM" }
        require(sampleRate > 0 && channels in 1..8 && bitsPerSample in setOf(8, 16, 24, 32)) {
            "Thông số WAV không hợp lệ"
        }
        val byteRate = sampleRate.toLong() * channels * bitsPerSample / 8L
        require(byteRate <= 0xffff_ffffL) { "Byte rate WAV vượt giới hạn" }
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(HEADER_SIZE)

        fun ascii(offset: Int, value: String) = value.forEachIndexed { index, char ->
            header[offset + index] = char.code.toByte()
        }
        fun le16(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = (value ushr 8 and 0xff).toByte()
        }
        fun le32(offset: Int, value: Long) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = (value ushr 8 and 0xff).toByte()
            header[offset + 2] = (value ushr 16 and 0xff).toByte()
            header[offset + 3] = (value ushr 24 and 0xff).toByte()
        }

        ascii(0, "RIFF")
        le32(4, pcmBytes + 36)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, channels)
        le32(24, sampleRate.toLong())
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, bitsPerSample)
        ascii(36, "data")
        le32(40, pcmBytes)
        return header
    }
}
