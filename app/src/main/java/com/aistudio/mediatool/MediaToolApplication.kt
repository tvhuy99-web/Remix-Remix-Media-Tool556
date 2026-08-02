package com.aistudio.mediatool

import android.app.Application
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
        // Native crash và low-memory kill chỉ có thể được khôi phục ở tiến trình kế tiếp.
        ProcessExitDiagnostics.recoverPreviousExit(this)
    }
}
