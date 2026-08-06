from pathlib import Path
import base64
import re
import zlib

bootstrap = Path(__file__).with_name("patch_spatial_object_renderer_v2.py").read_text(encoding="utf-8")
match = re.search(r'PAYLOAD = "([A-Za-z0-9+/=]+)"', bootstrap)
if not match:
    raise RuntimeError("Missing compressed Spatial renderer patch payload")

patch = zlib.decompress(base64.b64decode(match.group(1))).decode("utf-8")
old = "            append('\\n')\n            append(session.output.orEmpty())"
new = "            append('\\\\n')\n            append(session.output.orEmpty())"
if old not in patch and new not in patch:
    raise RuntimeError("Could not locate loudness log separator patch")
patch = patch.replace(old, new, 1)
exec(compile(patch, "patch_spatial_object_renderer_v2.py", "exec"))
