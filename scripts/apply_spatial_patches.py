#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
controls = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/components/SpatialAudioControls.kt"
text = controls.read_text(encoding="utf-8")
text = text.replace("import androidx.compose.foundation.layout.weight\n", "")
text = text.replace("import androidx.compose.material3.ExposedDropdownMenu\n", "")
controls.write_text(text, encoding="utf-8")

print("Đã áp dụng bản vá compile Spatial Audio")
