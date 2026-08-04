#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/aistudio/mediatool/core/audio/SynchronizedStemMixer.kt")
text = path.read_text(encoding="utf-8")
replacements = (
    ("import android.media.AudioManager\n", ""),
    ("            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)\n", ""),
)
for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match, found {count}: {old!r}")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Removed explicit generated AudioTrack session id")
