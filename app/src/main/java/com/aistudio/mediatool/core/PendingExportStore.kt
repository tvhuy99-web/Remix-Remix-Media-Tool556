package com.aistudio.mediatool.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor

/**
 * Tracks result documents that were created directly in the user's default SAF folder but have not
 * been explicitly kept yet. A pending document is deleted when the user abandons it. Persisting the
 * URI set also lets the next app launch clean up files left behind by a process kill or crash.
 */
object PendingExportStore {
    private const val PREFS = "pending_exports"
    private const val KEY_URIS = "pending_uris"
    private val lock = Any()

    fun register(context: Context, uri: Uri) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty().toMutableSet()
            next += uri.toString()
            check(prefs.edit().putStringSet(KEY_URIS, next).commit()) {
                "Không thể ghi trạng thái tệp chờ lưu"
            }
        }
        DiagnosticLogger.info(
            component = "PendingExportStore",
            event = "pending_export_registered",
            fields = mapOf("destination_id" to DiagnosticRedactor.stableId(uri.toString())),
        )
    }

    fun commit(context: Context, uri: Uri) {
        unregister(context, uri)
        DiagnosticLogger.info(
            component = "PendingExportStore",
            event = "pending_export_committed",
            fields = mapOf("destination_id" to DiagnosticRedactor.stableId(uri.toString())),
        )
    }

    fun discard(context: Context, uri: Uri): Boolean {
        val appContext = context.applicationContext
        val deleted = deleteDocument(appContext, uri)
        if (deleted || !documentExists(appContext, uri)) {
            unregister(appContext, uri)
        }
        DiagnosticLogger.info(
            component = "PendingExportStore",
            event = "pending_export_discarded",
            fields = mapOf(
                "destination_id" to DiagnosticRedactor.stableId(uri.toString()),
                "deleted" to deleted,
            ),
        )
        return deleted
    }

    fun cleanupAbandoned(context: Context): Int {
        val appContext = context.applicationContext
        val pending = synchronized(lock) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_URIS, emptySet())
                .orEmpty()
                .toList()
        }
        var cleaned = 0
        pending.forEach { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@forEach
            if (discard(appContext, uri)) cleaned++
        }
        if (pending.isNotEmpty()) {
            DiagnosticLogger.info(
                component = "PendingExportStore",
                event = "startup_pending_cleanup",
                fields = mapOf("pending_count" to pending.size, "deleted_count" to cleaned),
            )
        }
        return cleaned
    }

    fun isPending(context: Context, uri: Uri): Boolean = synchronized(lock) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_URIS, emptySet())
            .orEmpty()
            .contains(uri.toString())
    }

    private fun unregister(context: Context, uri: Uri) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty().toMutableSet()
            if (next.remove(uri.toString())) {
                check(prefs.edit().putStringSet(KEY_URIS, next).commit()) {
                    "Không thể cập nhật trạng thái tệp chờ lưu"
                }
            }
        }
    }

    private fun deleteDocument(context: Context, uri: Uri): Boolean {
        val deletedByDocumentsContract = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
        if (deletedByDocumentsContract) return true
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    private fun documentExists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
