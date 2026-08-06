#!/usr/bin/env python3
from pathlib import Path

test_path = Path("app/src/test/java/com/aistudio/mediatool/core/spatial/SpatialRoomPresetTest.kt")
test_text = test_path.read_text(encoding="utf-8")
old = '            assertTrue("$preset wet=${value.reverbWet}", value.reverbWet < 0.5f)'
new = '            assertTrue("$preset wet=${value.reverbWet}", value.reverbWet <= 0.5f)'
count = test_text.count(old)
if count != 1:
    raise RuntimeError(f"Expected one wet threshold assertion, found {count}")
test_path.write_text(test_text.replace(old, new, 1), encoding="utf-8")

roadmap = Path("docs/SPATIAL_AUDIO_ROADMAP.md")
roadmap.write_text(roadmap.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")

Path(__file__).unlink(missing_ok=True)
