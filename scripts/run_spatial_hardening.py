from pathlib import Path
import subprocess
import sys

patch = Path(__file__).with_name("patch_spatial_hardening.py")
lines = patch.read_text(encoding="utf-8").splitlines()
for index, line in enumerate(lines):
    if line == "once(p," and index + 1 < len(lines) and "reflection_duration_seconds" in lines[index + 1]:
        lines[index:index + 6] = [
            "once(p,",
            "    r'''         << \",\\\"true_effect_mix\\\":true}\"''',",
            "    r'''         << \",\\\"reflection_headroom_db\\\":\" << kReflectionHeadroomDb",
            "         << \",\\\"true_effect_mix\\\":true}\"''')",
        ]
        break
else:
    if "reflection_headroom_db" not in patch.read_text(encoding="utf-8"):
        raise RuntimeError("Could not locate the reflection JSON patch block")

patch.write_text("\n".join(lines) + "\n", encoding="utf-8")
subprocess.run([sys.executable, str(patch)], check=True)
