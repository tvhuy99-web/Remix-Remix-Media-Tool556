#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []
NOTES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)


def balanced_kotlin(path: Path) -> str | None:
    text = path.read_text(encoding="utf-8")
    stack: list[tuple[str, int]] = []
    pairs = {')': '(', ']': '[', '}': '{'}
    opens = set(pairs.values())
    i = 0
    line = 1
    state = "code"
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        tri = text[i:i + 3]
        if ch == "\n":
            line += 1
        if state == "line_comment":
            if ch == "\n":
                state = "code"
        elif state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                i += 1
        elif state == "string":
            if ch == "\\":
                i += 1
            elif ch == '"':
                state = "code"
        elif state == "triple":
            if tri == '"""':
                state = "code"
                i += 2
        elif state == "char":
            if ch == "\\":
                i += 1
            elif ch == "'":
                state = "code"
        else:
            if ch == "/" and nxt == "/":
                state = "line_comment"
                i += 1
            elif ch == "/" and nxt == "*":
                state = "block_comment"
                i += 1
            elif tri == '"""':
                state = "triple"
                i += 2
            elif ch == '"':
                state = "string"
            elif ch == "'":
                state = "char"
            elif ch in opens:
                stack.append((ch, line))
            elif ch in pairs:
                if not stack or stack[-1][0] != pairs[ch]:
                    return f"{path.relative_to(ROOT)}:{line}: dấu {ch} không khớp"
                stack.pop()
        i += 1
    if state in {"block_comment", "string", "triple", "char"}:
        return f"{path.relative_to(ROOT)}: kết thúc trong trạng thái {state}"
    if stack:
        ch, start_line = stack[-1]
        return f"{path.relative_to(ROOT)}:{start_line}: dấu {ch} chưa đóng"
    return None


required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "gradlew",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/cpp/CMakeLists.txt",
    "app/src/main/cpp/demucs_jni.cpp",
    "app/src/main/java/com/aistudio/mediatool/core/ml/DemucsNativeBridge.kt",
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt",
    "scripts/prepare_demucs_cpp.sh",
    "scripts/inspect_apks.py",
    ".github/workflows/build-apk.yml",
    "README.md",
    "CHANGELOG.md",
    "THIRD_PARTY_NOTICES.md",
]
for rel in required:
    check((ROOT / rel).is_file(), f"Thiếu tệp bắt buộc: {rel}")

for xml in sorted((ROOT / "app/src/main/res").rglob("*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]:
    try:
        ET.parse(xml)
    except Exception as exc:
        ERRORS.append(f"XML lỗi {xml.relative_to(ROOT)}: {exc}")

try:
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
    versions = catalog.get("versions", {})
    libraries = catalog.get("libraries", {})
    check(versions.get("agp") == "8.13.2", "AGP không phải 8.13.2")
    check(versions.get("ffmpegKit") == "8.1.7", "FFmpegKit không phải 8.1.7")
    check(versions.get("smartException") == "0.2.1", "Smart Exception không phải 0.2.1")
    check("onnxruntime" not in versions, "Version catalog vẫn chứa ONNX Runtime")
    check("onnxruntime-android" not in libraries, "Version catalog vẫn chứa dependency ONNX Runtime")
except Exception as exc:
    ERRORS.append(f"Version catalog TOML lỗi: {exc}")

build_gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
for token, message in [
    ('namespace = "com.aistudio.mediatool"', "Namespace không đúng"),
    ('applicationId = "com.aistudio.mediatool"', "Application ID không đúng"),
    ('versionCode = 9', "versionCode không phải 9"),
    ('versionName = "1.3.4"', "versionName không phải 1.3.4"),
    ('abiFilters += "arm64-v8a"', "Bản phân phối chưa giới hạn arm64-v8a"),
    ('path = file("src/main/cpp/CMakeLists.txt")', "Gradle chưa bật CMake Demucs"),
    ('libs.ffmpeg.kit.full', "Thiếu FFmpegKit"),
]:
    check(token in build_gradle, message)
check("libs.onnxruntime.android" not in build_gradle, "Gradle vẫn phụ thuộc ONNX Runtime")
check("armeabi-v7a" not in build_gradle and "x86_64" not in build_gradle, "Build vẫn đóng gói ABI ngoài ARM64")

registry = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt").read_text(encoding="utf-8")
for token, message in [
    ("83_994_361L", "Dung lượng Demucs FT Vocals ghim không đúng"),
    ("19186500a45a551a034d96e9500415ebe73c8bd570bf55337ddc8cc8f53a9120", "SHA Demucs FT Vocals ghim không đúng"),
    ("5f5daffffcf06ad7b27a7285da327e18ea62068a", "Commit weights Demucs chưa ghim"),
    ("demucs-ht-v4-ft-vocals-native-f16-v1", "Thiếu ID model native mặc định"),
]:
    check(token in registry, message)
check("953_292_899" not in registry, "Registry vẫn chứa Mel-Band 953 MB")
check("304_330_587" not in registry, "Registry vẫn chứa Demucs ONNX 304 MB")
check(".onnx" not in registry.lower(), "Registry vẫn trỏ model ONNX")

separator = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt").read_text(encoding="utf-8")
for token, message in [
    ("nativeBridge.separate", "AudioSeparator chưa gọi Demucs native"),
    ("nativeBridge.cancel", "AudioSeparator chưa hủy Demucs native"),
    ("FFmpegKit.cancel", "AudioSeparator chưa hủy FFmpeg"),
    ("-f f32le", "Pipeline stem chưa giữ PCM float32"),
    ("native_inference_start", "Pipeline thiếu log bắt đầu native inference"),
    ("native_inference_complete", "Pipeline thiếu log hoàn tất native inference"),
    ("createdOutputs", "Pipeline thiếu transaction output"),
    ("outputsCommitted", "Pipeline thiếu commit output"),
]:
    check(token in separator, message)
check("ai.onnxruntime" not in separator and "OrtSession" not in separator, "AudioSeparator vẫn chứa ONNX")

jni = (ROOT / "app/src/main/cpp/demucs_jni.cpp").read_text(encoding="utf-8")
for token, message in [
    ("load_demucs_model", "JNI chưa tải model Demucs"),
    ("demucs_inference", "JNI chưa gọi inference Demucs"),
    ("VOCALS_SOURCE = 3", "JNI ánh xạ vocals sai"),
    ("mix(channel, offset + frame) - vocal", "JNI chưa tạo instrumental từ mix trừ vocals"),
    ("OUT_OF_MEMORY", "JNI thiếu lỗi bộ nhớ ổn định"),
]:
    check(token in jni, message)

cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
check("mediatool_demucs" in cmake, "CMake thiếu thư viện mediatool_demucs")
check(".deps/demucs.cpp" in cmake, "CMake chưa dùng nguồn Demucs ghim")
check("DEMUCS_SOURCES" in cmake, "CMake chưa biên dịch nguồn demucs.cpp")
check((ROOT / ".deps/demucs.cpp/src/model.hpp").is_file(), "Nguồn demucs.cpp ghim chưa được chuẩn bị")
check((ROOT / ".deps/demucs.cpp/vendor/eigen/Eigen/Core").is_file(), "Eigen của demucs.cpp chưa được chuẩn bị")

workflow = (ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
for token in ["prepare_demucs_cpp.sh", "assembleDebug", "inspect_apks.py", "app-debug-apk"]:
    check(token in workflow, f"Workflow thiếu {token}")
check("assembleInternal --" not in workflow and "assembleDebugAndroidTest --" not in workflow, "Workflow còn build APK không cần thiết")

inspect = (ROOT / "scripts/inspect_apks.py").read_text(encoding="utf-8")
check("libmediatool_demucs.so" in inspect, "Kiểm tra APK chưa bắt buộc Demucs native")
check("onnxruntime" in inspect.lower() and "forbidden_onnx" in inspect, "Kiểm tra APK chưa cấm ONNX Runtime")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for token in [
    "FOREGROUND_SERVICE_MEDIA_PROCESSING",
    'android:foregroundServiceType="mediaProcessing"',
    'android:name=".MediaToolApplication"',
    "FileProvider",
]:
    check(token in manifest, f"Manifest thiếu {token}")
check("MANAGE_EXTERNAL_STORAGE" not in manifest, "Manifest không được xin MANAGE_EXTERNAL_STORAGE")

for kt in sorted((ROOT / "app/src/main/java").rglob("*.kt")):
    issue = balanced_kotlin(kt)
    if issue:
        ERRORS.append(issue)

try:
    with zipfile.ZipFile(ROOT / "gradle/wrapper/gradle-wrapper.jar") as jar:
        check("org/gradle/wrapper/GradleWrapperMain.class" in jar.namelist(), "Wrapper JAR thiếu main class")
except Exception as exc:
    ERRORS.append(f"Wrapper JAR lỗi: {exc}")

props = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
check(bool(re.search(r"distributionSha256Sum=[0-9a-f]{64}", props)), "Checksum Gradle wrapper thiếu hoặc sai")
check("gradle-8.13-bin.zip" in props, "Wrapper không dùng Gradle 8.13")

unit_tests = list((ROOT / "app/src/test").rglob("*Test.kt"))
android_tests = list((ROOT / "app/src/androidTest").rglob("*Test.kt"))
check(len(unit_tests) >= 13, "Cần ít nhất 13 unit test Kotlin")
check(len(android_tests) >= 1, "Thiếu instrumentation smoke test")

NOTES.append(f"Tests: {len(unit_tests)} unit, {len(android_tests)} instrumentation")
NOTES.append("Stem runtime: demucs.cpp native ARM64")
NOTES.append("Model: htdemucs_ft_vocals GGML FP16, 83,994,361 byte")

if ERRORS:
    print("VERIFY FAILED")
    for item in ERRORS:
        print(f"[ERROR] {item}")
    sys.exit(1)

print("VERIFY OK")
for note in NOTES:
    print(f"[OK] {note}")
