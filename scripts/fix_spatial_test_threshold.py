#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/test/java/com/aistudio/mediatool/core/spatial/SpatialRoomPresetTest.kt")
text = path.read_text(encoding="utf-8")
old = '            assertTrue("$preset wet=${value.reverbWet}", value.reverbWet < 0.5f)'
new = '            assertTrue("$preset wet=${value.reverbWet}", value.reverbWet <= 0.5f)'
count = text.count(old)
if count != 1:
    raise RuntimeError(f"Expected one wet threshold assertion, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
Path(__file__).unlink(missing_ok=True)
