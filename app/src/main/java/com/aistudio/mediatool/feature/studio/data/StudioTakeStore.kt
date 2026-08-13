package com.aistudio.mediatool.feature.studio.data

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PendingStudioTake(
    val projectId: String,
    val trackId: String,
    val takeId: String,
    val assetId: String,
    val recordedTimelineFrame: Long,
    val inputSampleRate: Int,
    val inputDeviceId: Int?,
    val latencyCompensationFrames: Long,
    val channelCount: Int,
    val partialRelativePath: String,
    val finalRelativePath: String,
    val journalRelativePath: String,
    val createdAt: Long,
)

data class FinalizedStudioTakeFile(
    val file: File,
    val relativePath: String,
    val info: StudioWavFile.Info,
)

class StudioTakeStore(private val projectStore: StudioProjectStore) {
    fun begin(
        projectId: String,
        trackId: String,
        recordedTimelineFrame: Long,
        inputSampleRate: Int,
        inputDeviceId: Int?,
        latencyCompensationFrames: Long = 0L,
        channelCount: Int = 1,
    ): PendingStudioTake {
        require(inputSampleRate > 0) { "Sample rate đầu vào không hợp lệ" }
        require(channelCount in 1..2) { "Số kênh thu Studio không hợp lệ" }
        val takeId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val pending = PendingStudioTake(
            projectId = projectId,
            trackId = trackId,
            takeId = takeId,
            assetId = assetId,
            recordedTimelineFrame = recordedTimelineFrame.coerceAtLeast(0L),
            inputSampleRate = inputSampleRate,
            inputDeviceId = inputDeviceId,
            latencyCompensationFrames = latencyCompensationFrames.coerceAtLeast(0L),
            channelCount = channelCount,
            partialRelativePath = "recovery/$takeId.partial.wav",
            finalRelativePath = "takes/take_${takeId.take(8)}.wav",
            journalRelativePath = "recovery/$takeId.json",
            createdAt = System.currentTimeMillis(),
        )
        val journal = projectStore.resolveAssetFile(projectId, pending.journalRelativePath)
        journal.parentFile?.mkdirs()
        writeJournal(journal, pending)
        return pending
    }

    fun loadPending(projectId: String): List<PendingStudioTake> {
        val recoveryDir = projectStore.resolveAssetFile(projectId, "recovery/.keep").parentFile
        if (recoveryDir?.isDirectory != true) return emptyList()
        return recoveryDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file -> runCatching { decodeJournal(file.readText(Charsets.UTF_8)) }.getOrNull() }
            .filter { it.projectId == projectId }
            .sortedBy { it.createdAt }
            .toList()
    }

    fun partialFile(pending: PendingStudioTake): File =
        projectStore.resolveAssetFile(pending.projectId, pending.partialRelativePath).also {
            it.parentFile?.mkdirs()
        }

    fun finalizeAudioFile(pending: PendingStudioTake): FinalizedStudioTakeFile? {
        val partial = partialFile(pending)
        val finalFile = projectStore.resolveAssetFile(pending.projectId, pending.finalRelativePath)
        finalFile.parentFile?.mkdirs()

        val source = when {
            finalFile.isFile && finalFile.length() > StudioWavFile.HEADER_BYTES -> finalFile
            partial.isFile && partial.length() > StudioWavFile.HEADER_BYTES -> partial
            else -> return null
        }
        val info = StudioWavFile.inspectCanonicalPcm16(source)
            ?: StudioWavFile.repairCanonicalPcm16(
                source,
                sampleRate = pending.inputSampleRate,
                channelCount = pending.channelCount,
            )
            ?: return null

        if (source != finalFile) {
            finalFile.delete()
            if (!source.renameTo(finalFile)) {
                source.inputStream().buffered().use { input ->
                    FileOutputStream(finalFile).buffered().use { output -> input.copyTo(output) }
                }
                FileOutputStream(finalFile, true).use { it.fd.sync() }
                source.delete()
            }
        }
        val finalInfo = StudioWavFile.inspectCanonicalPcm16(finalFile) ?: info
        return FinalizedStudioTakeFile(finalFile, pending.finalRelativePath, finalInfo)
    }

    fun commit(pending: PendingStudioTake) {
        projectStore.resolveAssetFile(pending.projectId, pending.journalRelativePath).delete()
        projectStore.resolveAssetFile(pending.projectId, pending.partialRelativePath).delete()
    }

    fun cancel(pending: PendingStudioTake) {
        projectStore.resolveAssetFile(pending.projectId, pending.partialRelativePath).delete()
        projectStore.resolveAssetFile(pending.projectId, pending.journalRelativePath).delete()
    }

    private fun writeJournal(target: File, pending: PendingStudioTake) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        val body = JSONObject().apply {
            put("projectId", pending.projectId)
            put("trackId", pending.trackId)
            put("takeId", pending.takeId)
            put("assetId", pending.assetId)
            put("recordedTimelineFrame", pending.recordedTimelineFrame)
            put("inputSampleRate", pending.inputSampleRate)
            put("inputDeviceId", pending.inputDeviceId ?: JSONObject.NULL)
            put("latencyCompensationFrames", pending.latencyCompensationFrames)
            put("channelCount", pending.channelCount)
            put("partialRelativePath", pending.partialRelativePath)
            put("finalRelativePath", pending.finalRelativePath)
            put("journalRelativePath", pending.journalRelativePath)
            put("createdAt", pending.createdAt)
        }.toString(2).toByteArray(Charsets.UTF_8)

        temporary.delete()
        FileOutputStream(temporary).use { output ->
            output.write(body)
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        check(target.isFile && target.length() > 0L) { "Không thể tạo journal cho Studio take" }
    }

    private fun decodeJournal(raw: String): PendingStudioTake {
        val json = JSONObject(raw)
        return PendingStudioTake(
            projectId = json.getString("projectId"),
            trackId = json.getString("trackId"),
            takeId = json.getString("takeId"),
            assetId = json.getString("assetId"),
            recordedTimelineFrame = json.optLong("recordedTimelineFrame", 0L),
            inputSampleRate = json.getInt("inputSampleRate"),
            inputDeviceId = if (json.isNull("inputDeviceId")) null else json.optInt("inputDeviceId"),
            latencyCompensationFrames = json.optLong("latencyCompensationFrames", 0L).coerceAtLeast(0L),
            channelCount = json.optInt("channelCount", 1).coerceIn(1, 2),
            partialRelativePath = json.getString("partialRelativePath"),
            finalRelativePath = json.getString("finalRelativePath"),
            journalRelativePath = json.getString("journalRelativePath"),
            createdAt = json.optLong("createdAt", 0L),
        )
    }
}