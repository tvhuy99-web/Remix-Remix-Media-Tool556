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
    state = "code"
    line = 1
    i = 0
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
    "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "app/src/main/AndroidManifest.xml",
    "README.md", "PROJECT_STATUS.md", "CHANGELOG.md", "THIRD_PARTY_NOTICES.md",
    "docs/ARCHITECTURE.md", "docs/ADDING_STEM_MODELS.md",
    "docs/MEL_BAND_ROFORMER_INTEGRATION.md", "docs/DIAGNOSTICS.md",
    "docs/RELEASE_CHECKLIST.md", "keystore.properties.example",
    "scripts/run_core_smoke.sh", "scripts/test_wrapper_bootstrap.py",
    "app/src/main/assets/third_party_notices.txt",
]
for rel in required:
    check((ROOT / rel).is_file(), f"Thiếu tệp bắt buộc: {rel}")

xml_files = sorted((ROOT / "app/src/main/res").rglob("*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]
for xml in xml_files:
    try:
        ET.parse(xml)
    except Exception as exc:
        ERRORS.append(f"XML lỗi {xml.relative_to(ROOT)}: {exc}")

try:
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
    versions = catalog.get("versions", {})
    for key, expected, label in [
        ("agp", "8.13.2", "AGP"),
        ("ffmpegKit", "8.1.7", "FFmpegKit"),
        ("smartException", "0.2.1", "Smart Exception"),
    ]:
        check(versions.get(key) == expected, f"{label} không phải {expected}")
except Exception as exc:
    ERRORS.append(f"Version catalog TOML lỗi: {exc}")

text_files = [p for p in ROOT.rglob("*") if p.is_file() and ".git" not in p.parts and "build" not in p.parts]
zero_files = [p.relative_to(ROOT) for p in text_files if p.stat().st_size == 0]
check(not zero_files, "Có tệp rỗng: " + ", ".join(map(str, zero_files)))
check(not list(ROOT.rglob("*.aar")), "Không được chứa AAR cục bộ")
check(not (ROOT / ".env").exists() and not (ROOT / ".env.example").exists(), "Không được phụ thuộc .env")

code_and_build = list((ROOT / "app").rglob("*.kt")) + [ROOT / "app/build.gradle.kts", ROOT / "settings.gradle.kts"]
for path in code_and_build:
    check("com.example" not in path.read_text(encoding="utf-8"), f"Package mẫu còn trong {path.relative_to(ROOT)}")

build_gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
for token, message in [
    ('namespace = "com.aistudio.mediatool"', "Namespace không đúng"),
    ('applicationId = "com.aistudio.mediatool"', "Application ID không đúng"),
    ("libs.ffmpeg.kit.full", "Thiếu dependency FFmpegKit Maven"),
    ("libs.onnxruntime.android.qnn", "Thiếu dependency ONNX Runtime QNN"),
    ("versionCode = 10", "versionCode không phải 10"),
    ('versionName = "1.3.6"', "versionName không phải 1.3.6"),
    ('create("internal")', "Thiếu build type internal"),
    ('initWith(getByName("release"))', "Internal không kế thừa release"),
    ("assembleInternal", "Thông báo release chưa hướng người dùng sang assembleInternal"),
    ('signingConfigs.getByName("debug")', "Internal chưa ký debug"),
    ('abiFilters += "arm64-v8a"', "Bản phân phối chưa giới hạn arm64-v8a"),
]:
    check(token in build_gradle, message)
check("isMinifyEnabled = true" in build_gradle and "isShrinkResources = true" in build_gradle, "Release chưa bật tối ưu")
check("if (hasReleaseKeystore)" in build_gradle and "Bản release yêu cầu keystore.properties" in build_gradle, "Release chưa bắt buộc keystore")
check('else {\n                signingConfig = signingConfigs.getByName("debug")' not in build_gradle, "Release vẫn fallback sang debug key")
check("armeabi-v7a" not in build_gradle and "x86_64" not in build_gradle, "Build vẫn đóng gói ABI chưa được FFmpegKit hỗ trợ")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for token in [
    "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "FOREGROUND_SERVICE_MEDIA_PROCESSING", "android.intent.action.TTS_SERVICE",
    'android:foregroundServiceType="mediaProcessing"', "FileProvider",
    "${applicationId}.provider", 'android:name=".MediaToolApplication"',
]:
    check(token in manifest, f"Manifest thiếu {token}")
check("MANAGE_EXTERNAL_STORAGE" not in manifest, "Manifest không được xin MANAGE_EXTERNAL_STORAGE")

try:
    with zipfile.ZipFile(ROOT / "gradle/wrapper/gradle-wrapper.jar") as jar:
        check("org/gradle/wrapper/GradleWrapperMain.class" in jar.namelist(), "Wrapper JAR thiếu main class")
except Exception as exc:
    ERRORS.append(f"Wrapper JAR lỗi: {exc}")

props = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
check(bool(re.search(r"distributionSha256Sum=([0-9a-f]{64})", props)), "Checksum Gradle wrapper thiếu hoặc sai định dạng")
check("gradle-8.13-bin.zip" in props, "Wrapper không dùng Gradle 8.13")

screens_dir = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens"
for screen in sorted(screens_dir.glob("*Screen.kt")):
    if screen.name != "MainScreen.kt":
        check("ToolScaffold(" in screen.read_text(encoding="utf-8"), f"{screen.name} chưa dùng ToolScaffold")

for kt in sorted((ROOT / "app/src/main/java").rglob("*.kt")):
    issue = balanced_kotlin(kt)
    if issue:
        ERRORS.append(issue)

ml_dir = ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml"
downloader = (ml_dir / "ModelDownloader.kt").read_text(encoding="utf-8")
registry = (ml_dir / "StemModelRegistry.kt").read_text(encoding="utf-8")
for token, message in [
    ("165_612_636", "Dung lượng HT-Demucs FT Vocals ghim không đúng"),
    ("0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce", "SHA HT-Demucs FT Vocals ghim không đúng"),
    ("2ef0d757d3e226d0da85fb8c71514f464fcabdd0", "Commit HT-Demucs FT Vocals ghim không đúng"),
    ("953_292_899", "Dung lượng Mel-Band RoFormer ghim không đúng"),
    ("64a4f3bee48fbe7d971b23875adc924ed004c3533f49672592641dddc0f6f561", "SHA Mel-Band RoFormer ghim không đúng"),
    ("60cb6b4b97e41b42f7ff16c2e386f47a8cc7e50a", "Commit Mel-Band RoFormer ghim không đúng"),
]:
    check(token in registry, message)
check("frames = 352_800" in registry and "overlapFrames = 176_400" in registry, "Chunk contract Mel-Band RoFormer không đúng")
check("Content-Range" in downloader and "suspendCancellableCoroutine" in downloader, "Tải model chưa hỗ trợ resume/hủy")
check("call.cancel()" in downloader and "AtomicBoolean" in downloader and "resumeWith(Result" in downloader, "OkHttp call chưa gắn cancellation an toàn")

separator = (ml_dir / "AudioSeparator.kt").read_text(encoding="utf-8")
for token, message in [
    ("FFmpegKit.cancel", "AudioSeparator chưa hủy FFmpeg"),
    ("setTerminate(true)", "AudioSeparator chưa hủy ONNX"),
    ("provider_forced_by_model", "Model QNN chưa bắt buộc dùng QNN GPU"),
    ("HTDEMUCS_FT_VOCALS_QNN_ID", "Model QNN chưa bắt buộc dùng QNN GPU"),
    ("musicFromMixMinusVocals", "HT-Demucs vocals chưa tạo instrumental từ mix trừ vocals"),
    ("createdOutputs", "AudioSeparator chưa cleanup output theo transaction"),
    ("outputsCommitted", "AudioSeparator chưa cleanup output theo transaction"),
    ("sharedInputBufferDirect", "AudioSeparator thiếu buffer tensor đầu vào"),
    ("-f f32le", "Pipeline stem chưa giữ PCM float32"),
    ("createReflectPaddedPcm", "Mel-Band RoFormer thiếu reflect padding ở biên"),
    ("AudioNormalization.GLOBAL_MONO_MEAN_STD", "Chuẩn hóa model chưa theo descriptor"),
]:
    check(token in separator, message)
check("addQnn" in separator and "backend_type" in separator, "AudioSeparator chưa cấu hình QNN GPU")
check("session.disable_cpu_ep_fallback" not in separator, "QNN GPU vẫn khóa CPU fallback")
check('"cpu_fallback_disabled" to false' in separator, "Log QNN chưa xác nhận CPU fallback được bật")
check("onnx_provider_attempt_failed" in separator, "AudioSeparator thiếu log từng lần fallback provider")
check("provider_chain" in separator and "OnnxAcceleration.XNNPACK" in separator, "QNN thiếu chuỗi dự phòng XNNPACK/CPU")
check("CPU fallback đã bị khóa" not in separator, "AudioSeparator còn thông báo sai rằng CPU fallback bị khóa")
check("inference_chunk_complete" in separator and "ffmpeg_failed" in separator, "Stem pipeline thiếu log phase/chunk")
check("INPUT GỐC" not in separator and "VOCAL OUT" not in separator, "Stem pipeline còn ghi mẫu âm thanh vào log")
check("catch (error: Exception)" in separator and "catch (error: Throwable)" in separator, "Fallback provider/OOM chưa tách biệt")

diagnostics_dir = ROOT / "app/src/main/java/com/aistudio/mediatool/core/diagnostics"
diagnostic_logger = (diagnostics_dir / "DiagnosticLogger.kt").read_text(encoding="utf-8")
diagnostic_report = (diagnostics_dir / "DiagnosticReportManager.kt").read_text(encoding="utf-8")
diagnostic_redactor = (diagnostics_dir / "DiagnosticRedactor.kt").read_text(encoding="utf-8")
check("diagnostics-current.jsonl" in diagnostic_logger and "MAX_FILE_BYTES" in diagnostic_logger, "Logger thiếu JSONL/rotation")
check("QUEUE_CAPACITY" in diagnostic_logger and "MediaTool-Diagnostics" in diagnostic_logger, "Logger chưa có worker/hàng đợi giới hạn")
check("recordCrashSync" in diagnostic_logger and "uncaught_exception" in diagnostic_logger, "Logger thiếu crash capture")
check("clearLogs" in diagnostic_logger and "log_session" in diagnostic_logger, "Logger thiếu xóa log/tách phiên")
diagnostic_card = (ROOT / "app/src/main/java/com/aistudio/mediatool/ui/components/DiagnosticReportCard.kt").read_text(encoding="utf-8")
check("Xóa nhật ký" in diagnostic_card and "showClearConfirmation" in diagnostic_card, "Cài đặt thiếu nút xóa nhật ký có xác nhận")
check("sanitizeFfmpegLogs" in diagnostic_redactor and "<media-uri>" in diagnostic_redactor, "Logger thiếu che dữ liệu media")
check("summary.json" in diagnostic_report and "recent_process_exits" in diagnostic_report, "Gói chẩn đoán thiếu summary/exit history")
settings_screen = (ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/SettingsScreen.kt").read_text(encoding="utf-8")
check("DiagnosticReportCard" in settings_screen, "Cài đặt thiếu nút xuất nhật ký")
check("không fallback CPU" not in settings_screen, "Cài đặt còn mô tả sai chính sách QNN fallback")
check("fallback thông minh" in settings_screen, "Cài đặt chưa mô tả chuỗi fallback QNN")

core_dir = ROOT / "app/src/main/java/com/aistudio/mediatool/core"
recording_service = (core_dir / "media/RecordingService.kt").read_text(encoding="utf-8")
recording_manager = (core_dir / "media/RecordingManager.kt").read_text(encoding="utf-8")
wav_recorder = (core_dir / "media/WavRecorder.kt").read_text(encoding="utf-8")
task_store = (core_dir / "TaskStateStore.kt").read_text(encoding="utf-8")
check("registerCallback" in recording_service and "unregisterCallback" in recording_service, "MediaProjection callback chưa hoàn chỉnh")
check("WavRecorder.lastError" in recording_manager, "Lỗi thread WAV chưa được chuyển lên RecordingManager")
check("WavHeader.HEADER_SIZE.toLong()" in wav_recorder, "So sánh kích thước WAV chưa dùng Long")
check("stableStartedAt" in task_store, "TaskStateStore còn đặt lại startedAt khi cập nhật progress")

workflow = (ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
for token in ["assembleDebug", "assembleInternal", "assembleDebugAndroidTest", "inspect_apks.py"]:
    check(token in workflow, f"Workflow thiếu {token}")
check("assembleRelease" not in workflow, "CI không nên build release khi thiếu keystore")
try:
    import yaml  # type: ignore
    yaml.safe_load(workflow)
except ImportError:
    NOTES.append("PyYAML không có; chỉ kiểm tra workflow bằng token")
except Exception as exc:
    ERRORS.append(f"Workflow YAML lỗi: {exc}")

unit_tests = list((ROOT / "app/src/test").rglob("*Test.kt"))
android_tests = list((ROOT / "app/src/androidTest").rglob("*Test.kt"))
check(len(unit_tests) >= 13, "Cần ít nhất 13 unit test Kotlin")
check(len(android_tests) >= 1, "Thiếu instrumentation smoke test")
check(
    "diagnosticReportIsExportableAndRedactsUris" in "\n".join(p.read_text(encoding="utf-8") for p in android_tests),
    "Instrumentation test chưa kiểm tra ZIP chẩn đoán/redaction",
)
NOTES.append(f"Tests: {len(unit_tests)} unit, {len(android_tests)} instrumentation")

kotlin_files = list((ROOT / "app/src/main/java").rglob("*.kt"))
source_bytes = sum(p.stat().st_size for p in kotlin_files)
NOTES.append(f"Kotlin: {len(kotlin_files)} tệp, {source_bytes:,} byte")
NOTES.append(f"XML: {len(xml_files)} tệp")
NOTES.append(f"Tổng tệp dự án kiểm tra: {len(text_files)}")

if ERRORS:
    print("VERIFY FAILED")
    for item in ERRORS:
        print(f"[ERROR] {item}")
    sys.exit(1)

print("VERIFY OK")
for note in NOTES:
    print(f"[OK] {note}")
