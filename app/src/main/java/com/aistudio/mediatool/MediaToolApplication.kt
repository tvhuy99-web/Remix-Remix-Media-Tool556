package com.aistudio.mediatool

import android.app.Application
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
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
    }
}
