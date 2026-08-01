package com.aistudio.mediatool

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.aistudio.mediatool.core.PersistentTaskState
import com.aistudio.mediatool.core.PersistentTaskStatus
import com.aistudio.mediatool.core.TaskStateStore
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticReportManager
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun fileProviderCanShareResultFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.cacheDir, "results").apply { mkdirs() }, "smoke.txt")
        file.writeText("ok")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        assertEquals("content", uri.scheme)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM, uri)
        assertTrue(intent.hasExtra(Intent.EXTRA_STREAM))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
    @Test
    fun taskStatesAreIsolatedAndOutputOrderIsPreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "task-smoke").apply { mkdirs() }
        val vocal = File(root, "vocals.wav").apply { writeBytes(byteArrayOf(1)) }
        val music = File(root, "music.wav").apply { writeBytes(byteArrayOf(2)) }
        val record = File(root, "record.wav").apply { writeBytes(byteArrayOf(3)) }

        TaskStateStore.save(
            context,
            PersistentTaskState("stem-1", "stem", PersistentTaskStatus.SUCCESS, outputPaths = listOf(vocal.path, music.path)),
        )
        TaskStateStore.save(
            context,
            PersistentTaskState("record-1", "recording", PersistentTaskStatus.SUCCESS, outputPaths = listOf(record.path)),
        )

        assertEquals(listOf(vocal.path, music.path), TaskStateStore.load(context, "stem")?.outputPaths)
        assertEquals(listOf(record.path), TaskStateStore.load(context, "recording")?.outputPaths)

        TaskStateStore.clear(context, "stem")
        TaskStateStore.clear(context, "recording")
        root.deleteRecursively()
    }

    @Test
    fun diagnosticReportIsExportableAndRedactsUris() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DiagnosticLogger.info(
            component = "SmokeTest",
            event = "privacy_probe",
            message = "content://private/diagnostic-smoke",
        )
        val report = DiagnosticReportManager.createReport(context)

        assertTrue(report.isFile && report.length() > 0L)
        ZipFile(report).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("summary.json" in names)
            assertTrue("README.txt" in names)
            val logs = names.filter { it.startsWith("logs/") }.joinToString("\n") { name ->
                zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
            }
            assertTrue("content://private/diagnostic-smoke" !in logs)
            assertTrue("<media-uri>" in logs)
        }
        report.delete()
    }

}
