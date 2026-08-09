package com.aistudio.mediatool.feature.studio.data

import android.content.Context
import android.net.Uri
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class StudioAssetStore(
    private val context: Context,
    private val projectStore: StudioProjectStore,
) {
    fun importBeat(projectId: String, source: Uri): StudioAsset {
        DocumentUtils.persistReadPermission(context, source)
        val displayName = DocumentUtils.displayName(context, source)
        val extension = displayName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val assetId = UUID.randomUUID().toString()
        val baseName = DocumentUtils.sanitizeFileName(displayName.substringBeforeLast('.'))
            .ifBlank { "beat" }
            .take(48)
        val fileName = buildString {
            append("beat_")
            append(baseName)
            append('_')
            append(assetId.take(8))
            if (extension != null) append('.').append(extension)
        }
        val relativePath = "assets/$fileName"
        val target = projectStore.resolveAssetFile(projectId, relativePath)
        target.parentFile?.mkdirs()
        target.delete()

        try {
            context.contentResolver.openInputStream(source)?.buffered()?.use { input ->
                FileOutputStream(target).buffered().use { output -> input.copyTo(output) }
            } ?: error("Không thể đọc nhạc beat đã chọn")
            require(target.isFile && target.length() > 0L) { "Nhạc beat không chứa dữ liệu" }
            FileOutputStream(target, true).use { it.fd.sync() }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }

        return StudioAsset(
            id = assetId,
            kind = StudioAssetKind.BEAT,
            relativePath = relativePath,
            displayName = displayName,
            mimeType = context.contentResolver.getType(source),
            bytes = target.length(),
        )
    }

    fun fileFor(projectId: String, asset: StudioAsset): File =
        projectStore.resolveAssetFile(projectId, asset.relativePath)
}
