#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL = ROOT / "scripts/apply_mdx23c_phase1.py"
source = ORIGINAL.read_text(encoding="utf-8")

old = '''replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    "\\\"effective_backend\\\" to engine.backend,",
    "\\\"effective_backend\\\" to engine.backendLabel,",
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    "\\\"effective_backend\\\" to engine.backend,",
    "\\\"effective_backend\\\" to engine.backendLabel,",
)
'''
new = '''audio_path = ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt"
audio_text = audio_path.read_text(encoding="utf-8")
backend_needle = '"effective_backend" to engine.backend,'
if audio_text.count(backend_needle) != 2:
    raise RuntimeError(
        f"MdxAudioSeparator.kt: expected two backend log matches, "
        f"found {audio_text.count(backend_needle)}"
    )
audio_path.write_text(
    audio_text.replace(backend_needle, '"effective_backend" to engine.backendLabel,'),
    encoding="utf-8",
)
'''
if source.count(old) != 1:
    raise RuntimeError("Cannot locate duplicated backend-log patch in phase-1 script")
source = source.replace(old, new, 1)
exec(compile(source, str(ORIGINAL), "exec"), {"__name__": "__main__", "__file__": str(ORIGINAL)})
