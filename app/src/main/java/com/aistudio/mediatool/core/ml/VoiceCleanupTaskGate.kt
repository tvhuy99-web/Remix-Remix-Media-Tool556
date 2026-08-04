package com.aistudio.mediatool.core.ml

/** Serializes foreground-service tasks and rejects stale completion callbacks. */
internal class VoiceCleanupTaskGate {
    private var generation = 0L
    private var state = State.IDLE

    @Synchronized
    fun tryStart(): Long? {
        if (state != State.IDLE) return null
        generation += 1L
        state = State.RUNNING
        return generation
    }

    @Synchronized
    fun beginStop(token: Long): Boolean {
        if (token != generation || state == State.IDLE) return false
        state = State.STOPPING
        return true
    }

    @Synchronized
    fun isRunning(token: Long): Boolean = token == generation && state == State.RUNNING

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation && state != State.IDLE

    @Synchronized
    fun finish(token: Long): Boolean {
        if (!isCurrent(token)) return false
        state = State.IDLE
        return true
    }

    private enum class State {
        IDLE,
        RUNNING,
        STOPPING,
    }
}
