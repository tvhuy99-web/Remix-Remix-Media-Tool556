#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"Expected {expected} matches in {path}, found {count}: {old!r}")
    target.write_text(text.replace(old, new), encoding="utf-8")


replace_exact(
    "app/src/main/java/com/aistudio/mediatool/core/audio/SynchronizedStemMixer.kt",
    "takeIf(Float::isFinite)",
    "takeIf { it.isFinite() }",
    expected=3,
)
replace_exact(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/SynchronizedStemMixerCard.kt",
    "onDispose(controller::close)",
    "onDispose { controller.close() }",
)
replace_exact(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    "                StemMixerCard(items)\n",
    "                SynchronizedStemMixerCard(success)\n",
)

print("Synchronized stem mixer wired into StemScreen")
