package com.aistudio.mediatool

import android.app.Application
import com.aistudio.mediatool.core.PendingExportStore
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.ProcessExitDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class MediaToolApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val delegating = AtomicBoolean(false)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DiagnosticLogger.recordCrashSync(thread, error)
            if (delegating.compareAndSet(false, true)) {
                if (previous != null) previous.uncaughtException(thread, error) else exitProcess(10)
            }
        }
        DiagnosticLogger.initialize(this)
        // Khôi phục nguyên nhân native crash hoặc low-memory kill ở lần mở kế tiếp.
        ProcessExitDiagnostics.recoverPreviousExit(this)
        // Kết quả ghi thẳng vào SAF nhưng chưa được người dùng bấm Lưu sẽ được dọn ở lần mở sau.
        Thread(
            { PendingExportStore.cleanupAbandoned(this) },
            "pending-export-cleanup",
        ).apply { isDaemon = true }.start()
    }
}
