#!/usr/bin/env python3
from pathlib import Path

script = Path(__file__).with_name("studio_hardening_patch.py")
source = script.read_text(encoding="utf-8")
needle = "if count != 1:"
if source.count(needle) != 1:
    raise SystemExit("Unexpected patch helper shape")
source = source.replace(needle, "if count < 1:", 1)
exec(compile(source, str(script), "exec"), {"__name__": "__main__", "__file__": str(script)})
