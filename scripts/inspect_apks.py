#!/usr/bin/env python3
from __future__ import annotations

import sys
import zipfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
paths = [Path(arg) for arg in sys.argv[1:]]
if not paths:
    paths = sorted((ROOT / "app/build/outputs/apk").rglob("*.apk"))

if not paths:
    raise SystemExit("Không tìm thấy APK để kiểm tra")

for apk in paths:
    if not apk.is_file():
        raise SystemExit(f"APK không tồn tại: {apk}")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if "AndroidManifest.xml" not in names:
            raise SystemExit(f"{apk.name}: thiếu AndroidManifest.xml")
        if not any(name.startswith("classes") and name.endswith(".dex") for name in names):
            raise SystemExit(f"{apk.name}: thiếu classes*.dex")
        native = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        if not native:
            raise SystemExit(f"{apk.name}: không có thư viện native")
        packaged_abis = sorted(set(name.split("/")[1] for name in native))
        if packaged_abis != ["arm64-v8a"]:
            raise SystemExit(f"{apk.name}: ABI không hợp lệ, chỉ cho phép arm64-v8a: {packaged_abis}")
        libcxx_by_abi = Counter(name.split("/")[1] for name in native if name.endswith("/libc++_shared.so"))
        duplicates = {abi: count for abi, count in libcxx_by_abi.items() if count != 1}
        if duplicates:
            raise SystemExit(f"{apk.name}: số bản libc++_shared.so không hợp lệ: {duplicates}")
        ffmpeg_markers = (
            "libffmpegkit.so",
            "libavcodec.so",
            "libavformat.so",
            "libavutil.so",
        )
        missing_ffmpeg = [marker for marker in ffmpeg_markers if not any(name.endswith(f"/{marker}") for name in native)]
        if missing_ffmpeg:
            raise SystemExit(f"{apk.name}: thiếu thư viện FFmpeg bắt buộc: {missing_ffmpeg}")
        print(f"[OK] {apk.name}: {len(native)} native libraries, ABI={packaged_abis}")
