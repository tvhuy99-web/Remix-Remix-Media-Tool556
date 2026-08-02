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


def text(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def balanced_kotlin(path: Path) -> str | None:
    source = path.read_text(encoding="utf-8")
    pairs = {")": "(", "]": "[", "}": "{"}
    stack: list[tuple[str, int]] = []
    line = 1
    state = "code"
    index = 0

    while index < len(source):
        ch = source[index]
        nxt = source[index + 1] if index + 1 < len(source) else ""
        tri = source[index:index + 3]
        if ch == "\n":
            line += 1

        if state == "line_comment":
            if ch == "\n":
                state = "code"
        elif state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                index += 1
        elif state == "string":
            if ch == "\\":
                index += 1
            elif ch == '"':
                state = "code"
        elif state == "triple":
            if tri == '"""':
                state = "code"
                index += 2
        elif state == "char":
            if ch == "\\":
                index += 1
            elif ch == "'":
                state = "code"
        else:
            if ch == "/" and nxt == "/":
                state = "line_comment"
                index += 1
            elif ch == "/" and nxt == "*":
                state = "block_comment"
                index += 1
            elif tri == '"""':
                state = "triple"
                index += 2
            elif ch == '"':
                state = "string"
            elif ch == "'":
                state = "char"
            elif ch in "([{":
                stack.append((ch, line))
            elif ch in pairs:
                if not stack or stack[-1][0] != pairs[ch]:
                    return f"{path.relative_to(ROOT)}:{line}: dấu {ch} không khớp"
                stack.pop()
        index += 1

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
    "app/src/main/java/com/aistudio/mediatool/core/SettingsManager.kt",
    "app/src/main/java/com/aistudio/mediatool/core/ml/DemucsNativeBridge.kt",
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt",
    "app/src/main/java/com/aistudio/mediatool/ui/screens/SettingsScreen.kt",
    "scripts/prepare_demucs_cpp.sh",
    "scripts/inspect_apks.py",
    ".github/workflows/build-apk.yml",
    "README.md",
    "CHANGELOG.md",
    "THIRD_PARTY_NOTICES.md",
]
for rel in required:
    check((ROOT / rel).is_file(), f"Thiếu tệp bắt buộc: {rel}")

for xml in sorted((ROOT / "app/src/main/res").rglob("*.xml")) + [
    ROOT / "app/src/main/AndroidManifest.xml"
]:
    try:
        ET.parse(xml)
    except Exception as exc:
        ERRORS.append(f"XML lỗi {xml.relative_to(ROOT)}: {exc}")

try:
    catalog = tomllib.loads(text("gradle/libs.versions.toml"))
    versions = catalog.get("versions", {})
    libraries = catalog.get("libraries", {})
    check(versions.get("agp") == "8.13.2", "AGP không phải 8.13.2")
    check(versions.get("ffmpegKit") == "8.1.7", "FFmpegKit không phải 8.1.7")
    check(versions.get("smartException") == "0.2.1", "Smart Exception không phải 0.2.1")
    check("onnxruntime" not in versions, "Version catalog vẫn chứa ONNX Runtime")
    check("onnxruntime-android" not in libraries, "Version catalog vẫn chứa ONNX Runtime")
except Exception as exc:
    ERRORS.append(f"Version catalog TOML lỗi: {exc}")

build_gradle = text("app/build.gradle.kts")
for token, message in [
    ('namespace = "com.aistudio.mediatool"', "Namespace không đúng"),
    ('applicationId = "com.aistudio.mediatool"', "Application ID không đúng"),
    ("versionCode = 10", "versionCode không phải 10"),
    ('versionName = "1.3.5"', "versionName không phải 1.3.5"),
    ('abiFilters += "arm64-v8a"', "Bản phân phối chưa giới hạn arm64-v8a"),
    ('path = file("src/main/cpp/CMakeLists.txt")', "Gradle chưa bật CMake Demucs"),
    ("libs.ffmpeg.kit.full", "Thiếu FFmpegKit"),
]:
    check(token in build_gradle, message)
check("libs.onnxruntime.android" not in build_gradle, "Gradle vẫn phụ thuộc ONNX Runtime")
check(
    "armeabi-v7a" not in build_gradle and "x86_64" not in build_gradle,
    "Build vẫn đóng gói ABI ngoài ARM64",
)

registry = text("app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt")
for token, message in [
    ("83_994_361L", "Dung lượng Demucs FT Vocals ghim không đúng"),
    (
        "19186500a45a551a034d96e9500415ebe73c8bd570bf55337ddc8cc8f53a9120",
        "SHA Demucs FT Vocals ghim không đúng",
    ),
    ("5f5daffffcf06ad7b27a7285da327e18ea62068a", "Commit weights Demucs chưa ghim"),
    ("demucs-ht-v4-ft-vocals-native-f16-v1", "Thiếu ID model native mặc định"),
]:
    check(token in registry, message)
check(".onnx" not in registry.lower(), "Registry vẫn trỏ model ONNX")
check("953_292_899" not in registry, "Registry vẫn chứa Mel-Band 953 MB")
check("304_330_587" not in registry, "Registry vẫn chứa Demucs ONNX 304 MB")

separator = text("app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt")
for token, message in [
    ("nativeBridge.separate", "AudioSeparator chưa gọi Demucs native"),
    ("nativeBridge.cancel", "AudioSeparator chưa hủy Demucs native"),
    ("SettingsManager.getNumThreads", "AudioSeparator chưa truyền số worker đã chọn"),
    ("-f f32le", "Pipeline stem chưa giữ PCM float32"),
    ("native_inference_start", "Pipeline thiếu log bắt đầu native inference"),
    ("native_inference_complete", "Pipeline thiếu log hoàn tất native inference"),
    ("createdOutputs", "Pipeline thiếu transaction output"),
    ("outputsCommitted", "Pipeline thiếu commit output"),
]:
    check(token in separator, message)
check("ai.onnxruntime" not in separator and "OrtSession" not in separator, "AudioSeparator vẫn chứa ONNX")

settings = text("app/src/main/java/com/aistudio/mediatool/core/SettingsManager.kt")
for token, message in [
    ("intArrayOf(1, 2, 4)", "Cài đặt worker không giới hạn đúng 1/2/4"),
    ("coerceIn(0, 2)", "Cài đặt worker chưa tự hạ giá trị 8 cũ"),
]:
    check(token in settings, message)

settings_screen = text("app/src/main/java/com/aistudio/mediatool/ui/screens/SettingsScreen.kt")
for token, message in [
    ("Demucs native CPU", "UI chưa hiển thị engine native thực tế"),
    ("Số vùng Demucs xử lý song song", "UI chưa mô tả worker song song"),
    ("4 worker - nhanh nhất", "UI thiếu lựa chọn 4 worker"),
]:
    check(token in settings_screen, message)
check('"8 luồng"' not in settings_screen, "UI vẫn quảng bá 8 luồng không tồn tại")
check('listOf("CPU", "NNAPI", "XNNPACK")' not in settings_screen, "UI vẫn quảng bá accelerator không hỗ trợ")

jni = text("app/src/main/cpp/demucs_jni.cpp")
for token, message in [
    ("load_demucs_model", "JNI chưa tải model Demucs"),
    ("demucs_inference", "JNI chưa gọi inference Demucs"),
    ("std::thread", "JNI chưa tạo worker inference thật"),
    ("effective_worker_count", "JNI chưa giới hạn worker theo độ dài"),
    ("OVERLAP_FRAMES", "JNI thiếu overlap giữa các worker"),
    ("sum_weights", "JNI thiếu chuẩn hóa overlap-add"),
    ("Eigen::setNbThreads(1)", "JNI chưa ngăn oversubscription Eigen"),
    ("VOCALS_SOURCE = 3", "JNI ánh xạ vocals sai"),
    ("mix(channel, offset + frame) - vocal", "JNI chưa tạo instrumental từ mix trừ vocals"),
    ("OUT_OF_MEMORY", "JNI thiếu lỗi bộ nhớ ổn định"),
]:
    check(token in jni, message)

cmake = text("app/src/main/cpp/CMakeLists.txt")
for token, message in [
    ("mediatool_demucs", "CMake thiếu thư viện mediatool_demucs"),
    (".deps/demucs.cpp", "CMake chưa dùng nguồn Demucs ghim"),
    ("DEMUCS_SOURCES", "CMake chưa biên dịch nguồn demucs.cpp"),
    ("Threads::Threads", "CMake chưa liên kết worker threads"),
    ("-ffp-contract=fast", "CMake chưa bật FMA contract"),
]:
    check(token in cmake, message)
check((ROOT / ".deps/demucs.cpp/src/model.hpp").is_file(), "Nguồn demucs.cpp ghim chưa được chuẩn bị")
check((ROOT / ".deps/demucs.cpp/vendor/eigen/Eigen/Core").is_file(), "Eigen của demucs.cpp chưa được chuẩn bị")

workflow = text(".github/workflows/build-apk.yml")
for token in ["prepare_demucs_cpp.sh", "assembleDebug", "inspect_apks.py", "app-debug-apk"]:
    check(token in workflow, f"Workflow thiếu {token}")
check(
    "assembleInternal --" not in workflow and "assembleDebugAndroidTest --" not in workflow,
    "Workflow còn build APK không cần thiết",
)

inspect = text("scripts/inspect_apks.py")
check("libmediatool_demucs.so" in inspect, "Kiểm tra APK chưa bắt buộc Demucs native")
check(
    "onnxruntime" in inspect.lower() and "forbidden_onnx" in inspect,
    "Kiểm tra APK chưa cấm ONNX Runtime",
)

manifest = text("app/src/main/AndroidManifest.xml")
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
        check(
            "org/gradle/wrapper/GradleWrapperMain.class" in jar.namelist(),
            "Wrapper JAR thiếu main class",
        )
except Exception as exc:
    ERRORS.append(f"Wrapper JAR lỗi: {exc}")

props = text("gradle/wrapper/gradle-wrapper.properties")
check(bool(re.search(r"distributionSha256Sum=[0-9a-f]{64}", props)), "Checksum Gradle wrapper thiếu hoặc sai")
check("gradle-8.13-bin.zip" in props, "Wrapper không dùng Gradle 8.13")

unit_tests = list((ROOT / "app/src/test").rglob("*Test.kt"))
android_tests = list((ROOT / "app/src/androidTest").rglob("*Test.kt"))
check(len(unit_tests) >= 13, "Cần ít nhất 13 unit test Kotlin")
check(len(android_tests) >= 1, "Thiếu instrumentation smoke test")

NOTES.append(f"Tests: {len(unit_tests)} unit, {len(android_tests)} instrumentation")
NOTES.append("Stem runtime: demucs.cpp native ARM64, 1/2/4 parallel workers")
NOTES.append("Model: htdemucs_ft_vocals GGML FP16, 83,994,361 byte")

if ERRORS:
    print("VERIFY FAILED")
    for item in ERRORS:
        print(f"[ERROR] {item}")
    sys.exit(1)

print("VERIFY OK")
for note in NOTES:
    print(f"[OK] {note}")
