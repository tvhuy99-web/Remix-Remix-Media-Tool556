package com.aistudio.mediatool.feature.studio.data

import android.content.Context
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import java.util.UUID

class StudioGeneratedAssetStore(context: Context) {
    private val repository = StudioProjectRepository(context.applicationContext)

    fun commit(
        projectId: String,
        relativePath: String,
        displayName: String,
        processorId: String,
        processorLabel: String,
        processorConfig: String,
    ): Pair<StudioProject, StudioAsset> {
        val project = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
        val file = repository.resolveProjectFile(projectId, relativePath)
        val info = requireNotNull(StudioWavFile.inspectCanonicalPcm16(file)) {
            "Bản giọng được tạo không phải WAV PCM16 hợp lệ"
        }
        val asset = StudioAsset(
            id = UUID.randomUUID().toString(),
            kind = StudioAssetKind.DERIVED,
            relativePath = relativePath,
            displayName = displayName.take(120),
            mimeType = "audio/wav",
            bytes = file.length(),
            sourceAssetId = null,
            processorId = processorId,
            processorLabel = processorLabel,
            processorConfig = processorConfig,
            sampleRate = info.sampleRate,
            channelCount = info.channelCount,
            durationFrames = info.dataFrames,
        )
        return repository.save(project.copy(assets = project.assets + asset)) to asset
    }
}
