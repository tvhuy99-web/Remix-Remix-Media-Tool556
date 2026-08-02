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
        dex_names = [name for name in names if name.startswith("classes") and name.endswith(".dex")]
        if not dex_names:
            raise SystemExit(f"{apk.name}: thiếu classes*.dex")
        dex_bytes = b"".join(archive.read(name) for name in dex_names)
        required_classes = (
            b"Lcom/arthenica/smartexception/java/Exceptions;",
            b"Lcom/arthenica/ffmpegkit/FFmpegKit;",
        )
        missing_classes = [
            descriptor.decode("ascii") for descriptor in required_classes if descriptor not in dex_bytes
        ]
        if missing_classes:
            raise SystemExit(f"{apk.name}: thiếu lớp runtime bắt buộc: {missing_classes}")
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

        # AAR QNN Android chính thức liên kết tĩnh execution provider vào
        # libonnxruntime.so; backend Qualcomm GPU và system vẫn là các .so riêng.
        qnn_markers = (
            "libonnxruntime.so",
            "libonnxruntime4j_jni.so",
            "libQnnGpu.so",
            "libQnnSystem.so",
        )
        missing_qnn = [marker for marker in qnn_markers if not any(name.endswith(f"/{marker}") for name in native)]
        if missing_qnn:
            raise SystemExit(f"{apk.name}: thiếu thành phần QNN GPU bắt buộc: {missing_qnn}")
        if any(name.endswith("/libonnxruntime_providers_qnn.so") for name in native):
            raise SystemExit(f"{apk.name}: QNN provider layout ngoài dự kiến; hãy kiểm tra lại AAR")
        if any(name.endswith("/libmediatool_demucs.so") for name in native):
            raise SystemExit(f"{apk.name}: không được đóng gói demucs.cpp CPU-only")
        print(
            f"[OK] {apk.name}: {len(native)} native libraries, "
            f"ABI={packaged_abis}, ORT_QNN=static, QNN_GPU=present"
        )
