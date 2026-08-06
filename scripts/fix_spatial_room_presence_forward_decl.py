from pathlib import Path

path = Path("scripts/patch_spatial_room_presence_v3.py")
text = path.read_text(encoding="utf-8")
old = "helpers = r'''\nfloat shapeFrontRearSample(Resources* resources, int channel, float sample,"
new = "helpers = r'''\nvoid clearBuffer(IPLAudioBuffer& buffer, int frameSize);\n\nfloat shapeFrontRearSample(Resources* resources, int channel, float sample,"
if text.count(old) != 1:
    raise RuntimeError("Could not locate helper insertion point")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Added clearBuffer forward declaration to generated C++ patch")
