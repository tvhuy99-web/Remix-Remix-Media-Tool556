#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/aistudio/mediatool/core/audio/SynchronizedStemMixer.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match, found {count}: {old[:180]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "import java.util.concurrent.atomic.AtomicLong\n",
    "import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicLong\n",
)
replace_once(
'''    private val generation = AtomicLong(0L)
    private val gains = AtomicReference(FloatArray(tracks.size) { 1f })
''',
'''    private val generation = AtomicLong(0L)
    private val playbackRequested = AtomicBoolean(false)
    private val gains = AtomicReference(FloatArray(tracks.size) { 1f })
''',
)
replace_once(
'''        synchronized(lock) {
            if (released) return
            val currentTrack = activeAudioTrack.get()
''',
'''        synchronized(lock) {
            if (released) return
            playbackRequested.set(true)
            val currentTrack = activeAudioTrack.get()
''',
)
replace_once(
'''        synchronized(lock) {
            if (released) return
            runCatching { activeAudioTrack.get()?.pause() }
            _isPlaying.value = false
''',
'''        synchronized(lock) {
            if (released) return
            playbackRequested.set(false)
            runCatching { activeAudioTrack.get()?.pause() }
            _isPlaying.value = false
''',
)
replace_once(
'''            stopActiveLocked()
            _positionMs.value = clampedMs
            _error.value = null
''',
'''            stopActiveLocked()
            playbackRequested.set(playImmediately)
            _positionMs.value = clampedMs
            _error.value = null
''',
)
replace_once(
'''        generation.incrementAndGet()
        worker?.cancel()
''',
'''        generation.incrementAndGet()
        playbackRequested.set(false)
        worker?.cancel()
''',
)
replace_once(
'''                if (!playbackStarted) {
                    audioTrack.play()
''',
'''                if (!playbackStarted && playbackRequested.get()) {
                    audioTrack.play()
''',
)
replace_once(
'''                writtenBytes == 0 -> {
                    consecutiveZeroWrites += 1
                    if (consecutiveZeroWrites == 1) {
''',
'''                writtenBytes == 0 -> {
                    if (!playbackRequested.get()) {
                        consecutiveZeroWrites = 0
                        delay(PAUSED_WRITE_RETRY_MS)
                        continue
                    }
                    consecutiveZeroWrites += 1
                    if (consecutiveZeroWrites == 1) {
''',
)
replace_once(
'''            if (isCurrent(currentGeneration)) {
                _isPlaying.value = false
                synchronized(lock) {
''',
'''            if (isCurrent(currentGeneration)) {
                playbackRequested.set(false)
                _isPlaying.value = false
                synchronized(lock) {
''',
)
replace_once(
'''        private const val MAX_CONSECUTIVE_ZERO_WRITES = 20
        private const val ZERO_WRITE_RETRY_MS = 10L
''',
'''        private const val MAX_CONSECUTIVE_ZERO_WRITES = 20
        private const val ZERO_WRITE_RETRY_MS = 10L
        private const val PAUSED_WRITE_RETRY_MS = 20L
''',
)

path.write_text(text, encoding="utf-8")
print("Hardened mixer pause and retry state")
