#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import re
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "4.8.1"
ASSET_NAME = f"steamaudio_{VERSION}.zip"
ASSET_URL = f"https://github.com/ValveSoftware/steam-audio/releases/download/v{VERSION}/{ASSET_NAME}"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: cần đúng 1 vị trí, tìm thấy {count}")
    return text.replace(old, new, 1)


def regex_replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{label}: cần đúng 1 vị trí, tìm thấy {count}")
    return updated


def download_sdk(work: Path) -> tuple[Path, str, int]:
    archive = work / ASSET_NAME
    request = urllib.request.Request(
        ASSET_URL,
        headers={"User-Agent": "MediaTool-Spatial-Bootstrap/1.0"},
    )
    with urllib.request.urlopen(request, timeout=180) as response, archive.open("wb") as output:
        shutil.copyfileobj(response, output)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    return archive, digest, archive.stat().st_size


def find_one(root: Path, predicate, label: str) -> Path:
    matches = [path for path in root.rglob("*") if path.is_file() and predicate(path)]
    if len(matches) != 1:
        details = "\n".join(str(path.relative_to(root)) for path in matches[:20])
        raise RuntimeError(f"{label}: cần đúng 1 tệp, tìm thấy {len(matches)}\n{details}")
    return matches[0]


def install_sdk(work: Path) -> dict[str, object]:
    archive, archive_sha256, archive_size = download_sdk(work)
    extracted = work / "sdk"
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(extracted)

    header = find_one(
        extracted,
        lambda path: path.name == "phonon.h" and "include" in path.parts,
        "Steam Audio header",
    )
    library = find_one(
        extracted,
        lambda path: path.name == "libphonon.so" and "android-armv8" in path.parts,
        "Steam Audio Android ARMv8 library",
    )
    license_file = find_one(
        extracted,
        lambda path: path.name.upper() in {"LICENSE", "LICENSE.TXT"}
        and "steamaudio" in str(path.parent).lower(),
        "Steam Audio license",
    )

    header_target = ROOT / "app/src/main/cpp/third_party/steam_audio/include/phonon.h"
    library_target = ROOT / "app/src/main/jniLibs/arm64-v8a/libphonon.so"
    license_target = ROOT / "app/src/main/assets/licenses/steam_audio_apache_2.txt"
    for target in (header_target, library_target, license_target):
        target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(header, header_target)
    shutil.copy2(library, library_target)
    shutil.copy2(license_file, license_target)

    metadata = {
        "name": "Steam Audio SDK",
        "version": VERSION,
        "release_asset": ASSET_NAME,
        "release_url": ASSET_URL,
        "archive_bytes": archive_size,
        "archive_sha256": archive_sha256,
        "header_sha256": hashlib.sha256(header_target.read_bytes()).hexdigest(),
        "android_arm64_library_bytes": library_target.stat().st_size,
        "android_arm64_library_sha256": hashlib.sha256(library_target.read_bytes()).hexdigest(),
        "license": "Apache-2.0",
    }
    metadata_target = ROOT / "app/src/main/cpp/third_party/steam_audio/METADATA.json"
    metadata_target.parent.mkdir(parents=True, exist_ok=True)
    metadata_target.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


def patch_gradle() -> None:
    path = ROOT / "app/build.gradle.kts"
    text = path.read_text(encoding="utf-8")
    if "src/main/cpp/CMakeLists.txt" in text:
        return
    text = replace_once(
        text,
        '''        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner''',
        '''        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        testInstrumentationRunner''',
        "Gradle defaultConfig externalNativeBuild",
    )
    text = replace_once(
        text,
        '''    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources''',
        '''    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources''',
        "Gradle CMake path",
    )
    path.write_text(text, encoding="utf-8")


def patch_spatial_sources() -> None:
    controls = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/components/SpatialAudioControls.kt"
    text = controls.read_text(encoding="utf-8")
    if "androidx.compose.foundation.layout.weight" not in text:
        text = replace_once(
            text,
            "import androidx.compose.foundation.layout.padding\n",
            "import androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.weight\n",
            "Compose weight import",
        )
    controls.write_text(text, encoding="utf-8")

    engine = ROOT / "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioEngine.kt"
    text = engine.read_text(encoding="utf-8")
    if "import kotlinx.coroutines.flow.collect" not in text:
        text = replace_once(
            text,
            "import kotlinx.coroutines.flow.Flow\n",
            "import kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.collect\n",
            "Flow collect import",
        )
    text = replace_once(
        text,
        '''        } catch (error: Throwable) {
            output.delete()
            DiagnosticLogger.error(''',
        '''        } catch (error: LinkageError) {
            output.delete()
            DiagnosticLogger.error(
                component = TAG,
                event = "spatial_render_failed",
                sessionId = taskId,
                message = error.message,
                fields = value.diagnosticFields() + mapOf(
                    "source_id" to DiagnosticRedactor.stableId(inputSaf),
                    "failure_type" to error.javaClass.name,
                ),
                error = error,
            )
            emit(State.Error(error.message ?: "Thành phần native spatial audio chưa sẵn sàng"))
        } catch (error: Exception) {
            output.delete()
            DiagnosticLogger.error(''',
        "Không nuốt JVM Error",
    )
    engine.write_text(text, encoding="utf-8")


def patch_other_screen() -> None:
    path = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/OtherScreen.kt"
    text = path.read_text(encoding="utf-8")
    if "SpatialAudioControls(" in text:
        return

    text = replace_once(
        text,
        "import com.aistudio.mediatool.core.SettingsManager\n",
        '''import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialAudioEngine
import com.aistudio.mediatool.core.spatial.SpatialAudioPreferences
import com.aistudio.mediatool.ui.components.SpatialAudioControls
''',
        "OtherScreen spatial imports",
    )
    text = replace_once(
        text,
        "    val mediaEngine = remember { MediaEngine(context) }\n",
        '''    val mediaEngine = remember { MediaEngine(context) }
    val spatialAudioEngine = remember { SpatialAudioEngine(context, mediaEngine) }
''',
        "OtherScreen spatial engine",
    )
    text = replace_once(
        text,
        '''    var statusText by remember { mutableStateOf("Sẵn sàng") }

    var exoPlayer''',
        '''    var statusText by remember { mutableStateOf("Sẵn sàng") }
    var enableSpatialAudio by rememberSaveable { mutableStateOf(false) }
    var spatialAudioConfig by remember { mutableStateOf(SpatialAudioPreferences.load(context)) }

    fun updateSpatialAudioConfig(next: SpatialAudioConfig) {
        val normalized = next.normalized()
        spatialAudioConfig = normalized
        SpatialAudioPreferences.save(context, normalized)
    }

    val sofaLauncher = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.filesDir, "hrtf").apply { mkdirs() }
                    val target = File(directory, "custom_${System.currentTimeMillis()}.sofa")
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Không thể mở tệp SOFA" }
                        target.outputStream().use(input::copyTo)
                    }
                    require(target.length() > 0L) { "Tệp SOFA rỗng" }
                    withContext(Dispatchers.Main) {
                        spatialAudioConfig.customSofaPath?.let(::File)?.delete()
                        updateSpatialAudioConfig(spatialAudioConfig.copy(customSofaPath = target.absolutePath))
                        statusText = "Đã chọn HRTF SOFA: ${target.name}"
                    }
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        statusText = "Không thể nhập SOFA: ${error.message ?: "Tệp không hợp lệ"}"
                    }
                }
            }
        }
    }

    var exoPlayer''',
        "OtherScreen spatial state and SOFA picker",
    )

    text = text.replace("    var enable8d by rememberSaveable { mutableStateOf(false) }\n", "")
    for line in (
        "    var eightDStartMs by rememberSaveable { mutableStateOf(\"\") }\n",
        "    var eightDEndMs by rememberSaveable { mutableStateOf(\"\") }\n",
        "    var eightDCycle by rememberSaveable { mutableFloatStateOf(8f) }\n",
        "    var eightDRoomSize by rememberSaveable { mutableFloatStateOf(50f) }\n",
    ):
        text = text.replace(line, "")

    text = regex_replace_once(
        text,
        r'''\n                if \(enable8d\) \{\n                    val delay1 = .*?\n                    audioFilters \+= "aecho=0\.8:0\.9:\$delay1\|\$delay2:\$decay1\|\$decay2"\n                \}''',
        "",
        "Xóa apulsator + aecho 8D cũ",
    )

    pipeline_anchor = '''                if (audioFilters.isNotEmpty()) {
                    audioFilters += "alimiter=limit=0.9886:level=0:latency=1"
                }

                val command = buildString {'''
    pipeline_replacement = '''                if (audioFilters.isNotEmpty()) {
                    audioFilters += "alimiter=limit=0.9886:level=0:latency=1"
                }

                if (enableSpatialAudio) {
                    SpatialAudioPreferences.save(context, spatialAudioConfig)
                    spatialAudioEngine.process(
                        inputSaf = inputSaf,
                        output = output,
                        sourceDurationMs = sourceDurationMs,
                        config = spatialAudioConfig,
                        preFilters = audioFilters,
                        isVideoMode = isVideoMode,
                        modeIndex = modeIndex,
                        extension = extension,
                        preview = isPreview,
                    ).collect { state ->
                        when (state) {
                            is SpatialAudioEngine.State.Progress -> withContext(Dispatchers.Main) {
                                statusText = state.message
                                progress = state.percent
                            }
                            is SpatialAudioEngine.State.Success -> withContext(Dispatchers.Main) {
                                resultPath = output.absolutePath
                                pendingOutput = null
                                statusText = if (isPreview) {
                                    "Đã tạo mẫu Spatial Audio 10 giây"
                                } else {
                                    "Spatial Audio hoàn tất • RMS ${String.format(java.util.Locale.US, "%.1f", state.metrics.rmsDbfs)} dBFS"
                                }
                                progress = 100f
                                isProcessing = false
                                if (isPreview) playAudio(Uri.fromFile(output), true)
                            }
                            is SpatialAudioEngine.State.Error -> withContext(Dispatchers.Main) {
                                output.delete()
                                statusText = "Lỗi Spatial Audio: ${state.message}"
                                progress = 0f
                                isProcessing = false
                            }
                        }
                    }
                    return@launch
                }

                val command = buildString {'''
    text = replace_once(text, pipeline_anchor, pipeline_replacement, "Nối spatial pipeline")

    old_checkbox = '''                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = enable8d, onValueChange = { enable8d = it }, role = Role.Checkbox)
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = enable8d, onCheckedChange = null)
                        Text("Âm thanh không gian (Nhạc 8D)", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
'''
    text = replace_once(text, old_checkbox, "", "Xóa checkbox 8D cũ")

    old_controls_pattern = r'''\n                if \(enable8d\) \{\n                    TimeBlock\(eightDStartMs,.*?\n                \}\n\n                Card\(colors = CardDefaults\.cardColors'''
    new_controls = '''
                SpatialAudioControls(
                    enabled = enableSpatialAudio,
                    onEnabledChange = { enableSpatialAudio = it },
                    config = spatialAudioConfig,
                    onConfigChange = ::updateSpatialAudioConfig,
                    onPickSofa = { sofaLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    onClearSofa = {
                        spatialAudioConfig.customSofaPath?.let(::File)?.delete()
                        updateSpatialAudioConfig(spatialAudioConfig.copy(customSofaPath = null))
                    },
                )

                Card(colors = CardDefaults.cardColors'''
    text = regex_replace_once(text, old_controls_pattern, new_controls, "Thay UI 8D bằng SpatialAudioControls")

    if any(token in text for token in ("enable8d", "eightDCycle", "eightDRoomSize", "eightDStartMs", "eightDEndMs")):
        raise RuntimeError("OtherScreen vẫn còn tham chiếu engine 8D cũ")
    path.write_text(text, encoding="utf-8")


def patch_notices(metadata: dict[str, object]) -> None:
    markdown = ROOT / "THIRD_PARTY_NOTICES.md"
    text = markdown.read_text(encoding="utf-8")
    notice = "- Steam Audio SDK 4.8.1: Apache License 2.0; binary Android ARM64 và header được ghim bằng SHA-256 trong `app/src/main/cpp/third_party/steam_audio/METADATA.json`."
    if notice not in text:
        text = text.rstrip() + "\n" + notice + "\n"
    markdown.write_text(text, encoding="utf-8")

    asset_notice = ROOT / "app/src/main/assets/third_party_notices.txt"
    text = asset_notice.read_text(encoding="utf-8")
    block = "\nSteam Audio SDK 4.8.1\nLicense: Apache License 2.0\nSource: ValveSoftware/steam-audio, release v4.8.1\n"
    if "Steam Audio SDK 4.8.1" not in text:
        text = text.rstrip() + "\n" + block
    asset_notice.write_text(text, encoding="utf-8")


def verify_old_engine_removed() -> None:
    other = (ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/OtherScreen.kt").read_text(encoding="utf-8")
    if "if (enable8d)" in other or "Tùy chỉnh Nhạc 8D" in other:
        raise RuntimeError("Engine Nhạc 8D cũ chưa được loại bỏ hoàn toàn")
    if "SpatialAudioControls(" not in other or "spatialAudioEngine.process(" not in other:
        raise RuntimeError("Spatial Audio chưa được nối đầy đủ vào OtherScreen")


def main() -> int:
    work = ROOT / ".bootstrap-spatial"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    try:
        metadata = install_sdk(work)
        patch_gradle()
        patch_spatial_sources()
        patch_other_screen()
        patch_notices(metadata)
        verify_old_engine_removed()
        print(json.dumps(metadata, indent=2))
        print("Spatial Audio bootstrap hoàn tất")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
