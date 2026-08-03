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
    "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "app/src/main/AndroidManifest.xml",
    "README.md", "PROJECT_STATUS.md", "CHANGELOG.md", "THIRD_PARTY_NOTICES.md",
    "docs/ARCHITECTURE.md", "docs/ADDING_STEM_MODELS.md",
    "docs/DIAGNOSTICS.md", "docs/RELEASE_CHECKLIST.md", "keystore.properties.example",
    "scripts/run_core_smoke.sh", "scripts/test_wrapper_bootstrap.py",
    "app/src/main/assets/third_party_notices.txt",
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
    check(versions.get("agp") == "8.13.2", "AGP không phải 8.13.2")
    check(versions.get("ffmpegKit") == "8.1.7", "FFmpegKit không phải 8.1.7")
    check(versions.get("smartException") == "0.2.1", "Smart Exception không phải 0.2.1")
    check(versions.get("litert") == "2.1.6", "LiteRT không phải 2.1.6")
except Exception as exc:
    ERRORS.append(f"Version catalog TOML lỗi: {exc}")

text_files = [p for p in ROOT.rglob("*") if p.is_file() and ".git" not in p.parts and "build" not in p.parts]
zero_files = [p.relative_to(ROOT) for p in text_files if p.stat().st_size == 0]
check(not zero_files, "Có tệp rỗng: " + ", ".join(map(str, zero_files)))
check(not list(ROOT.rglob("*.aar")), "Không được chứa AAR cục bộ")
check(not (ROOT / ".env").exists() and not (ROOT / ".env.example").exists(), "Không được phụ thuộc .env")

code_and_build = list((ROOT / "app").rglob("*.kt")) + [ROOT / "app/build.gradle.kts", ROOT / "settings.gradle.kts"]
for path in code_and_build:
    body = path.read_text(encoding="utf-8")
    check("com.example" not in body, f"Package mẫu còn trong {path.relative_to(ROOT)}")

build_gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
check('namespace = "com.aistudio.mediatool"' in build_gradle, "Namespace không đúng")
check('applicationId = "com.aistudio.mediatool"' in build_gradle, "Application ID không đúng")
check("libs.ffmpeg.kit.full" in build_gradle, "Thiếu dependency FFmpegKit")
check("libs.onnxruntime.android" in build_gradle, "Thiếu dependency ONNX Runtime")
check("libs.litert" in build_gradle, "Thiếu dependency LiteRT")
check("versionCode = 8" in build_gradle, "versionCode không phải 8")
check('versionName = "1.3.3"' in build_gradle, "versionName không phải 1.3.3")
check('create("internal")' in build_gradle, "Thiếu build type internal")
check('initWith(getByName("release"))' in build_gradle, "Internal không kế thừa release")
check("isMinifyEnabled = true" in build_gradle and "isShrinkResources = true" in build_gradle, "Release chưa bật tối ưu")
check("assembleInternal" in build_gradle, "Thông báo release chưa hướng người dùng sang assembleInternal")
check('signingConfigs.getByName("debug")' in build_gradle, "Internal chưa ký debug")
check("if (hasReleaseKeystore)" in build_gradle and "Bản release yêu cầu keystore.properties" in build_gradle, "Release chưa bắt buộc keystore")
check('abiFilters += "arm64-v8a"' in build_gradle, "Bản phân phối chưa giới hạn arm64-v8a")
check("armeabi-v7a" not in build_gradle and "x86_64" not in build_gradle, "Build vẫn đóng gói ABI không hỗ trợ")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for token in [
    "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "FOREGROUND_SERVICE_MEDIA_PROCESSING", "android.intent.action.TTS_SERVICE",
    'android:foregroundServiceType="mediaProcessing"', "FileProvider", "${applicationId}.provider",
    'android:name=".MediaToolApplication"',
]:
    check(token in manifest, f"Manifest thiếu {token}")
check("MANAGE_EXTERNAL_STORAGE" not in manifest, "Manifest không được xin MANAGE_EXTERNAL_STORAGE")

try:
    with zipfile.ZipFile(ROOT / "gradle/wrapper/gradle-wrapper.jar") as jar:
        check("org/gradle/wrapper/GradleWrapperMain.class" in jar.namelist(), "Wrapper JAR thiếu main class")
except Exception as exc:
    ERRORS.append(f"Wrapper JAR lỗi: {exc}")

props = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
sha = re.search(r"distributionSha256Sum=([0-9a-f]{64})", props)
check(bool(sha), "Checksum Gradle wrapper thiếu hoặc sai định dạng")
check("gradle-8.13-bin.zip" in props, "Wrapper không dùng Gradle 8.13")

screens_dir = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens"
for screen in sorted(screens_dir.glob("*Screen.kt")):
    if screen.name == "MainScreen.kt":
        continue
    check("ToolScaffold(" in screen.read_text(encoding="utf-8"), f"{screen.name} chưa dùng ToolScaffold")

for kt in sorted((ROOT / "app/src/main/java").rglob("*.kt")):
    issue = balanced_kotlin(kt)
    if issue:
        ERRORS.append(issue)

downloader = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/ModelDownloader.kt").read_text(encoding="utf-8")
registry = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt").read_text(encoding="utf-8")
view_model = (ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/StemViewModel.kt").read_text(encoding="utf-8")
settings_screen = (ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/SettingsScreen.kt").read_text(encoding="utf-8")
mdx_engine = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/MdxLiteRtEngine.kt").read_text(encoding="utf-8")
check("66_848_828" in registry, "Dung lượng UVR LiteRT ghim không đúng")
check("5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58" in registry, "SHA UVR LiteRT ghim không đúng")
check("MEL_BAND_ROFORMER_ID" not in registry and "953_292_899" not in registry, "Mel-Band vẫn còn trong catalog")
check("UVR MDX-Net Voc FT" in registry and 'displayName = "Demucs"' in registry, "Catalog thiếu UVR hoặc Demucs")
check("setOf(OnnxAcceleration.CPU, OnnxAcceleration.XNNPACK)" in registry, "Demucs chưa giới hạn CPU/XNNPACK")
check("REMOVED_MEL_BAND_PREFIX" in view_model and "deleteRemovedMelBandFiles" in view_model, "Chưa xóa cache Mel-Band cũ")
check("OnnxAcceleration.XNNPACK.settingsIndex" in view_model, "Demucs chưa tự ưu tiên XNNPACK")
check("Luồng CPU" in settings_screen and "Bộ tăng tốc phần cứng" not in settings_screen, "Cài đặt AI chưa được rút gọn")
check("1 luồng" in settings_screen and "2 luồng" in settings_screen and "4 luồng" in settings_screen and "8 luồng" in settings_screen, "Thiếu lựa chọn luồng CPU")
check("numThreads = cpuThreads.coerceIn(1, 8)" in mdx_engine, "LiteRT CPU chưa dùng đúng số luồng")
check("LITERT_GPU_FP16" in mdx_engine and "LITERT_CPU_XNNPACK" in mdx_engine, "LiteRT thiếu GPU hoặc CPU fallback")
check("Content-Range" in downloader and "suspendCancellableCoroutine" in downloader, "Tải model chưa hỗ trợ resume/hủy")
check("call.cancel()" in downloader and "AtomicBoolean" in downloader and "resumeWith(Result" in downloader, "OkHttp call chưa gắn cancellation")

separator = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt").read_text(encoding="utf-8")
check("FFmpegKit.cancel" in separator, "AudioSeparator chưa hủy FFmpeg")
check("setTerminate(true)" in separator, "AudioSeparator chưa hủy ONNX")
check("createdOutputs" in separator and "outputsCommitted" in separator, "AudioSeparator chưa cleanup output")
check("sharedInputBufferDirect" in separator, "AudioSeparator thiếu buffer tensor đầu vào")
check("-f f32le" in separator, "Pipeline stem chưa giữ PCM float32")
check("AudioNormalization.GLOBAL_MONO_MEAN_STD" in separator, "Chuẩn hóa Demucs chưa theo descriptor")
check("inference_chunk_complete" in separator and "ffmpeg_failed" in separator, "Stem pipeline thiếu log")
check("INPUT GỐC" not in separator and "VOCAL OUT" not in separator, "Stem pipeline ghi mẫu âm thanh vào log")
check("catch (error: Exception)" in separator and "catch (error: Throwable)" in separator, "Fallback/OOM chưa tách biệt")

diagnostic_logger = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/diagnostics/DiagnosticLogger.kt").read_text(encoding="utf-8")
diagnostic_report = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/diagnostics/DiagnosticReportManager.kt").read_text(encoding="utf-8")
diagnostic_redactor = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/diagnostics/DiagnosticRedactor.kt").read_text(encoding="utf-8")
check("diagnostics-current.jsonl" in diagnostic_logger and "MAX_FILE_BYTES" in diagnostic_logger, "Logger thiếu JSONL/rotation")
check("QUEUE_CAPACITY" in diagnostic_logger and "MediaTool-Diagnostics" in diagnostic_logger, "Logger chưa có worker giới hạn")
check("recordCrashSync" in diagnostic_logger and "uncaught_exception" in diagnostic_logger, "Logger thiếu crash capture")
check("clearLogs" in diagnostic_logger and "log_session" in diagnostic_logger, "Logger thiếu xóa log hoặc phiên log")
check("sanitizeFfmpegLogs" in diagnostic_redactor and "<media-uri>" in diagnostic_redactor, "Logger thiếu che dữ liệu media")
check("summary.json" in diagnostic_report and "recent_process_exits" in diagnostic_report, "Gói chẩn đoán thiếu summary/exit history")
check("DiagnosticReportCard" in settings_screen and "Xóa nhật ký" in (ROOT / "app/src/main/java/com/aistudio/mediatool/ui/components/DiagnosticReportCard.kt").read_text(encoding="utf-8"), "Cài đặt thiếu quản lý nhật ký")

recording_service = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/media/RecordingService.kt").read_text(encoding="utf-8")
check("registerCallback" in recording_service and "unregisterCallback" in recording_service, "MediaProjection callback chưa hoàn chỉnh")
recording_manager = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/media/RecordingManager.kt").read_text(encoding="utf-8")
wav_recorder = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/media/WavRecorder.kt").read_text(encoding="utf-8")
task_store = (ROOT / "app/src/main/java/com/aistudio/mediatool/core/TaskStateStore.kt").read_text(encoding="utf-8")
check("WavRecorder.lastError" in recording_manager, "Lỗi thread WAV chưa được chuyển lên RecordingManager")
check("WavHeader.HEADER_SIZE.toLong()" in wav_recorder, "So sánh kích thước WAV chưa dùng Long")
check("stableStartedAt" in task_store, "TaskStateStore còn đặt lại startedAt")

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
NOTES.append(f"XML: {len(list((ROOT / 'app/src/main/res').rglob('*.xml'))) + 1} tệp")
NOTES.append(f"Tổng tệp dự án kiểm tra: {len(text_files)}")

if ERRORS:
    print("VERIFY FAILED")
    for item in ERRORS:
        print(f"[ERROR] {item}")
    sys.exit(1)

print("VERIFY OK")
for note in NOTES:
    print(f"[OK] {note}")
