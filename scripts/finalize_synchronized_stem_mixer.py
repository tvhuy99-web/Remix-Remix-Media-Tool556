#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old[:120]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


# Always cancel a still-active FFmpeg session when the pipe or AudioTrack exits early.
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/audio/SynchronizedStemMixer.kt",
    '''            if (sessionId >= 0L) {
                activeSessionId.compareAndSet(sessionId, -1L)
                if (!isCurrent(currentGeneration)) FFmpegKit.cancel(sessionId)
            }
''',
    '''            if (sessionId >= 0L && activeSessionId.compareAndSet(sessionId, -1L)) {
                FFmpegKit.cancel(sessionId)
            }
''',
)

# The generation guard proves that no newer worker owns this slot.
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/audio/SynchronizedStemMixer.kt",
    '''                synchronized(lock) {
                    if (worker?.isActive != true) worker = null
                }
''',
    '''                synchronized(lock) {
                    worker = null
                }
''',
)

# Remove the previous multi-ExoPlayer mixer implementation now that StemScreen uses one clock.
stem_path = Path("app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt")
stem_text = stem_path.read_text(encoding="utf-8")
start_marker = "@Composable\nprivate fun StemMixerCard(items: List<StemMixerItem>) {\n"
end_marker = "private fun shortModelName(model: StemModelDescriptor): String = when (model.id) {\n"
if stem_text.count(start_marker) != 1 or stem_text.count(end_marker) != 1:
    raise RuntimeError("Could not locate the legacy StemMixerCard block uniquely")
start = stem_text.index(start_marker)
end = stem_text.index(end_marker)
if end <= start:
    raise RuntimeError("Legacy mixer markers are out of order")
stem_text = stem_text[:start] + stem_text[end:]

unused_imports = (
    "import androidx.compose.foundation.layout.Row\n",
    "import androidx.compose.material.icons.Icons\n",
    "import androidx.compose.material.icons.filled.Pause\n",
    "import androidx.compose.material.icons.filled.PlayArrow\n",
    "import androidx.compose.material3.FilterChip\n",
    "import androidx.compose.material3.Icon\n",
    "import androidx.compose.material3.IconButton\n",
    "import androidx.compose.material3.Slider\n",
    "import androidx.compose.runtime.DisposableEffect\n",
    "import androidx.compose.runtime.mutableLongStateOf\n",
    "import androidx.compose.runtime.mutableStateMapOf\n",
    "import androidx.compose.ui.Alignment\n",
    "import androidx.compose.ui.semantics.clearAndSetSemantics\n",
    "import androidx.compose.ui.text.font.FontWeight\n",
    "import androidx.media3.common.MediaItem\n",
    "import androidx.media3.common.Player\n",
    "import androidx.media3.exoplayer.ExoPlayer\n",
    "import com.aistudio.mediatool.ui.components.formatDuration\n",
    "import kotlin.math.abs\n",
    "import kotlin.math.roundToInt\n",
    "import kotlinx.coroutines.delay\n",
    "import kotlinx.coroutines.isActive\n",
)
for import_line in unused_imports:
    count = stem_text.count(import_line)
    if count != 1:
        raise RuntimeError(f"Expected one legacy import, found {count}: {import_line!r}")
    stem_text = stem_text.replace(import_line, "", 1)
stem_path.write_text(stem_text, encoding="utf-8")

print("Final synchronized stem mixer cleanup applied")
