#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, rel: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected exactly one match, found {count}: {old[:120]!r}")
    return text.replace(old, new, 1)


rel = "gradle/libs.versions.toml"
text = read(rel)
text = replace_once(text, 'onnxruntime-android = { module = "com.microsoft.onnxruntime:onnxruntime-android", version.ref = "onnxruntime" }', 'onnxruntime-android-qnn = { module = "com.microsoft.onnxruntime:onnxruntime-android-qnn", version.ref = "onnxruntime" }', rel)
write(rel, text)

rel = "app/build.gradle.kts"
text = read(rel)
text = replace_once(text, 'versionCode = 8', 'versionCode = 10', rel)
text = replace_once(text, 'versionName = "1.3.3"', 'versionName = "1.3.6"', rel)
text = replace_once(text, 'implementation(libs.onnxruntime.android)', 'implementation(libs.onnxruntime.android.qnn)', rel)
write(rel, text)

rel = "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelContract.kt"
text = read(rel)
text = replace_once(text, '    XNNPACK(2),\n    ;', '    XNNPACK(2),\n    QNN_GPU(3),\n    ;', rel)
text = replace_once(text, '    val sources: StemSourceMap,\n    val allowedAccelerators: Set<OnnxAcceleration>,', '    val sources: StemSourceMap,\n    val musicFromMixMinusVocals: Boolean = false,\n    val allowedAccelerators: Set<OnnxAcceleration>,', rel)
write(rel, text)

rel = "app/src/main/java/com/aistudio/mediatool/core/SettingsManager.kt"
text = read(rel)
text = replace_once(text, 'fun getHardwareAccelIndex(context: Context): Int = prefs(context).getInt(KEY_HW_ACCEL_INDEX, 0).coerceIn(0, 2)', 'fun getHardwareAccelIndex(context: Context): Int = prefs(context).getInt(KEY_HW_ACCEL_INDEX, 3).coerceIn(0, 3)', rel)
text = replace_once(text, 'fun setHardwareAccelIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_HW_ACCEL_INDEX, value.coerceIn(0, 2)).apply()', 'fun setHardwareAccelIndex(context: Context, value: Int) = prefs(context).edit().putInt(KEY_HW_ACCEL_INDEX, value.coerceIn(0, 3)).apply()', rel)
write(rel, text)

rel = "app/src/main/java/com/aistudio/mediatool/core/ml/OnnxThreadingPolicy.kt"
text = read(rel)
text = replace_once(text, '    private const val XNNPACK_INDEX = 2\n', '    private const val XNNPACK_INDEX = 2\n    private const val QNN_GPU_INDEX = 3\n', rel)
text = replace_once(text, '''        return if (hardwareAccelerationIndex == XNNPACK_INDEX) {
            // XNNPACK sở hữu thread pool riêng; ORT không nên tạo thêm pool cạnh tranh.
            OnnxThreadingConfig(ortIntraOpThreads = 1, xnnpackThreads = safeThreads)
        } else {
            OnnxThreadingConfig(ortIntraOpThreads = safeThreads, xnnpackThreads = null)
        }
''', '''        return when (hardwareAccelerationIndex) {
            XNNPACK_INDEX -> {
                // XNNPACK sở hữu thread pool riêng; ORT không nên tạo thêm pool cạnh tranh.
                OnnxThreadingConfig(ortIntraOpThreads = 1, xnnpackThreads = safeThreads)
            }
            QNN_GPU_INDEX -> {
                // QNN GPU thực thi graph trên Adreno. CPU chỉ điều phối I/O và không cần pool ORT lớn.
                OnnxThreadingConfig(ortIntraOpThreads = 1, xnnpackThreads = null)
            }
            else -> OnnxThreadingConfig(ortIntraOpThreads = safeThreads, xnnpackThreads = null)
        }
''', rel)
write(rel, text)

rel = "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt"
text = read(rel)
text = replace_once(text, '    const val MEL_BAND_ROFORMER_ID = "melband-roformer-kj-vocals-v1"\n', '    const val HTDEMUCS_FT_VOCALS_QNN_ID = "htdemucs-ft-vocals-fp16-qnn-v1"\n    const val MEL_BAND_ROFORMER_ID = "melband-roformer-kj-vocals-v1"\n', rel)
anchor = '    val melBandRoFormerTwoStem = StemModelDescriptor(\n'
descriptor = '''    val htDemucsFtVocalsQnn = StemModelDescriptor(
        id = HTDEMUCS_FT_VOCALS_QNN_ID,
        displayName = "HT-Demucs v4 FT Vocals (QNN GPU)",
        description = "Model vocals fine-tuned, ưu tiên GPU Snapdragon; instrumental được lấy từ mix trừ vocals.",
        mode = StemMode.TWO_STEM,
        modelSpec = ModelSpec(
            url = "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/2ef0d757d3e226d0da85fb8c71514f464fcabdd0/htdemucs_ft_vocals_fp16weights.onnx?download=true",
            fileName = "htdemucs-ft-vocals-fp16-2ef0d757.onnx",
            familyPrefix = "htdemucs-ft-vocals-fp16-",
            expectedBytes = 165_612_636L,
            sha256 = "0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce",
        ),
        sampleRate = 44_100,
        channels = 2,
        chunking = ChunkingSpec(
            frames = 343_980,
            overlapFrames = 85_995,
            edgeFadeFrames = 85_995,
            overlapProfile = OverlapProfile.COMPLEMENTARY_SINE,
        ),
        normalization = AudioNormalization.GLOBAL_MONO_MEAN_STD,
        tensor = TensorContract(
            inputName = "mix",
            outputName = "stems",
            inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
            outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
            sourceCount = 4,
        ),
        sources = StemSourceMap(
            vocals = SourceMix(listOf(3)),
            music = SourceMix(listOf(0, 1, 2)),
        ),
        musicFromMixMinusVocals = true,
        allowedAccelerators = setOf(
            OnnxAcceleration.CPU,
            OnnxAcceleration.XNNPACK,
            OnnxAcceleration.QNN_GPU,
        ),
        deviceRequirements = DeviceRequirements(
            minimumTotalRamBytes = 6L * GIB,
            minimumAvailableRamBytes = 2L * GIB,
            userFacingSummary = "Khuyến nghị Snapdragon và còn ít nhất 2 GB RAM trống.",
        ),
        licenseName = "MIT",
        projectUrl = "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx",
    )

'''
text = replace_once(text, anchor, descriptor + anchor, rel)
text = replace_once(text, '''    val all: List<StemModelDescriptor> = listOf(
        melBandRoFormerTwoStem,
        demucsFourStem,
        demucsTwoStemLite,
    )
''', '''    val all: List<StemModelDescriptor> = listOf(
        htDemucsFtVocalsQnn,
        melBandRoFormerTwoStem,
        demucsFourStem,
        demucsTwoStemLite,
    )
''', rel)
write(rel, text)

rel = "app/src/main/java/com/aistudio/mediatool/ui/screens/SettingsScreen.kt"
text = read(rel)
text = replace_once(text, 'val hwList = listOf("CPU", "NNAPI", "XNNPACK")', 'val hwList = listOf("CPU", "NNAPI", "XNNPACK", "QNN GPU (Snapdragon, thử nghiệm)")', rel)
text = replace_once(text, '                            label = { Text("Bộ tăng tốc phần cứng (Hardware AI)", color = Color(0xFF673AB7), fontWeight = FontWeight.SemiBold) },\n                            trailingIcon =', '                            label = { Text("Bộ tăng tốc phần cứng (Hardware AI)", color = Color(0xFF673AB7), fontWeight = FontWeight.SemiBold) },\n                            supportingText = {\n                                if (hwAccelIndex == 3) {\n                                    Text("QNN GPU không fallback CPU: graph không tương thích sẽ báo lỗi thay vì chạy chậm âm thầm.")\n                                }\n                            },\n                            trailingIcon =', rel)
text = replace_once(text, '                            label = { Text("Số luồng xử lý CPU", color = Color(0xFFFF5722), fontWeight = FontWeight.SemiBold) },\n                            trailingIcon =', '                            label = { Text("Số luồng xử lý CPU", color = Color(0xFFFF5722), fontWeight = FontWeight.SemiBold) },\n                            supportingText = {\n                                if (hwAccelIndex == 3) Text("Không ảnh hưởng đến inference QNN GPU.")\n                            },\n                            trailingIcon =', rel)
write(rel, text)

rel = "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt"
text = read(rel)
text = replace_once(text, '''                2 -> {
                    val xnnpackThreads = requireNotNull(threading.xnnpackThreads)
                    // XNNPACK có pool riêng. Tắt spinning của ORT và giữ ORT intra-op = 1
                    // theo khuyến nghị chính thức để tránh hai pool tranh CPU.
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(hashMapOf("intra_op_num_threads" to xnnpackThreads.toString()))
                    logInfo(
                        "onnx_provider_config",
                        mapOf("provider" to OnnxAcceleration.XNNPACK, "threads" to xnnpackThreads),
                    )
                }
                else -> logInfo("onnx_provider_config", mapOf("provider" to OnnxAcceleration.CPU))
''', '''                2 -> {
                    val xnnpackThreads = requireNotNull(threading.xnnpackThreads)
                    // XNNPACK có pool riêng. Tắt spinning của ORT và giữ ORT intra-op = 1
                    // theo khuyến nghị chính thức để tránh hai pool tranh CPU.
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(hashMapOf("intra_op_num_threads" to xnnpackThreads.toString()))
                    logInfo(
                        "onnx_provider_config",
                        mapOf("provider" to OnnxAcceleration.XNNPACK, "threads" to xnnpackThreads),
                    )
                }
                3 -> {
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    setInterOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addQnn(hashMapOf("backend_type" to "gpu"))
                    logInfo(
                        "onnx_provider_config",
                        mapOf(
                            "provider" to OnnxAcceleration.QNN_GPU,
                            "backend_type" to "gpu",
                            "cpu_fallback_disabled" to true,
                        ),
                    )
                }
                else -> logInfo("onnx_provider_config", mapOf("provider" to OnnxAcceleration.CPU))
''', rel)
text = replace_once(text, '''        } catch (error: Exception) {
            if (requestedHardware == 0) throw error
            logError("Không cấu hình được bộ tăng tốc; chuyển sang CPU", error)
''', '''        } catch (error: Exception) {
            if (requestedAcceleration == OnnxAcceleration.QNN_GPU) {
                throw IllegalStateException(
                    "Không khởi tạo được QNN GPU. Thiết bị cần Snapdragon tương thích và QNN phải chạy toàn bộ graph.",
                    error,
                )
            }
            if (requestedHardware == 0) throw error
            logError("Không cấu hình được bộ tăng tốc; chuyển sang CPU", error)
''', rel)
text = replace_once(text, '''        } catch (error: Exception) {
            primaryOptions.close()
            if (requestedHardware == 0) throw error
            logError("Bộ tăng tốc không tạo được session cho ${model.displayName}; chuyển sang CPU", error)
''', '''        } catch (error: Exception) {
            primaryOptions.close()
            if (requestedAcceleration == OnnxAcceleration.QNN_GPU) {
                throw IllegalStateException(
                    "QNN GPU không thể nhận toàn bộ graph của ${model.displayName}; CPU fallback đã bị khóa để tránh chạy chậm âm thầm.",
                    error,
                )
            }
            if (requestedHardware == 0) throw error
            logError("Bộ tăng tốc không tạo được session cho ${model.displayName}; chuyển sang CPU", error)
''', rel)
text = replace_once(text, '''                        fun mixValue(mix: SourceMix, channel: Int, frame: Int): Float {
                            var value = 0f
                            mix.sourceIndices.forEach { source -> value += sourceValue(source, channel, frame) }
                            val denormalized = value * std + mean
                            return if (denormalized.isFinite()) denormalized else 0f
                        }
''', '''                        fun mixValue(mix: SourceMix, channel: Int, frame: Int): Float {
                            var value = 0f
                            mix.sourceIndices.forEach { source -> value += sourceValue(source, channel, frame) }
                            val denormalized = value * std + mean
                            return if (denormalized.isFinite()) denormalized else 0f
                        }

                        fun musicValue(vocals: Float, channel: Int, frame: Int): Float {
                            if (!model.musicFromMixMinusVocals) {
                                return mixValue(model.sources.music, channel, frame)
                            }
                            val original = chunkBufferFloat[frame * channels + channel]
                            val complement = original - vocals
                            return if (complement.isFinite()) complement else 0f
                        }
''', rel)
text = replace_once(text, '                                var m_val = mixValue(model.sources.music, ch, f)', '                                var m_val = musicValue(v_val, ch, f)', rel)
text = replace_once(text, '                                    val m_val = mixValue(model.sources.music, ch, f)', '                                    val m_val = musicValue(v_val, ch, f)', rel)
write(rel, text)

rel = "app/src/test/java/com/aistudio/mediatool/core/ml/OnnxThreadingPolicyTest.kt"
text = read(rel)
anchor = '''    @Test
    fun cpuAndNnapiKeepOrtThreadSetting() {
'''
text = replace_once(text, anchor, '''    @Test
    fun qnnGpuKeepsOrtSingleThreaded() {
        assertEquals(OnnxThreadingConfig(1, null), OnnxThreadingPolicy.resolve(3, 8))
    }

''' + anchor, rel)
write(rel, text)

rel = "app/src/test/java/com/aistudio/mediatool/core/ml/StemModelRegistryTest.kt"
text = read(rel)
text = replace_once(text, '    fun twoStemDefaultsToMelBandRoFormer() {\n        assertSame(\n            StemModelRegistry.melBandRoFormerTwoStem,', '    fun twoStemDefaultsToHtDemucsFtVocals() {\n        assertSame(\n            StemModelRegistry.htDemucsFtVocalsQnn,', rel)
anchor = '''    @Test
    fun melBandTensorAndChunkContractMatchesExport() {
'''
text = replace_once(text, anchor, '''    @Test
    fun htDemucsFtVocalsContractIsPinnedAndUsesMixComplement() {
        val model = StemModelRegistry.htDemucsFtVocalsQnn
        assertEquals(165_612_636L, model.modelSpec.expectedBytes)
        assertEquals("0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce", model.modelSpec.sha256)
        assertEquals("mix", model.tensor.inputName)
        assertEquals("stems", model.tensor.outputName)
        assertEquals(listOf(3), model.sources.vocals.sourceIndices)
        assertEquals(true, model.musicFromMixMinusVocals)
        assertEquals(setOf(OnnxAcceleration.CPU, OnnxAcceleration.XNNPACK, OnnxAcceleration.QNN_GPU), model.allowedAccelerators)
    }

''' + anchor, rel)
text = replace_once(text, '            StemModelRegistry.melBandRoFormerTwoStem,\n            StemModelRegistry.resolve(StemMode.TWO_STEM, StemModelRegistry.DEMUCS_4_STEM_ID),', '            StemModelRegistry.htDemucsFtVocalsQnn,\n            StemModelRegistry.resolve(StemMode.TWO_STEM, StemModelRegistry.DEMUCS_4_STEM_ID),', rel)
write(rel, text)

rel = "scripts/inspect_apks.py"
text = read(rel)
insert = '''        qnn_provider = [name for name in native if "onnxruntime_providers_qnn" in name]
        qnn_gpu = [name for name in native if "QnnGpu" in name]
        if not qnn_provider:
            raise SystemExit(f"{apk.name}: thiếu ONNX Runtime QNN provider")
        if not qnn_gpu:
            raise SystemExit(f"{apk.name}: thiếu Qualcomm QNN GPU backend")
        if any(name.endswith("/libmediatool_demucs.so") for name in native):
            raise SystemExit(f"{apk.name}: không được đóng gói demucs.cpp CPU-only")
'''
text = replace_once(text, '        print(f"[OK] {apk.name}: {len(native)} native libraries, ABI={packaged_abis}")', insert + '        print(f"[OK] {apk.name}: {len(native)} native libraries, ABI={packaged_abis}, QNN_GPU=present")', rel)
write(rel, text)

rel = "scripts/verify_project.py"
text = read(rel)
text = replace_once(text, 'check("libs.onnxruntime.android" in build_gradle, "Thiếu dependency ONNX Runtime")', 'check("libs.onnxruntime.android.qnn" in build_gradle, "Thiếu dependency ONNX Runtime QNN")', rel)
text = replace_once(text, 'check("versionCode = 8" in build_gradle, "versionCode không phải 8")', 'check("versionCode = 10" in build_gradle, "versionCode không phải 10")', rel)
text = replace_once(text, 'check(\'versionName = "1.3.3"\' in build_gradle, "versionName không phải 1.3.3")', 'check(\'versionName = "1.3.6"\' in build_gradle, "versionName không phải 1.3.6")', rel)
text = replace_once(text, 'check("953_292_899" in registry, "Dung lượng Mel-Band RoFormer ghim không đúng")', 'check("165_612_636" in registry, "Dung lượng HT-Demucs FT Vocals ghim không đúng")\ncheck("0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce" in registry, "SHA HT-Demucs FT Vocals ghim không đúng")\ncheck("2ef0d757d3e226d0da85fb8c71514f464fcabdd0" in registry, "Commit HT-Demucs FT Vocals ghim không đúng")\ncheck("953_292_899" in registry, "Dung lượng Mel-Band RoFormer ghim không đúng")', rel)
text = replace_once(text, 'check("setTerminate(true)" in separator, "AudioSeparator chưa hủy ONNX")', 'check("setTerminate(true)" in separator, "AudioSeparator chưa hủy ONNX")\ncheck("addQnn" in separator and "backend_type" in separator, "AudioSeparator chưa cấu hình QNN GPU")\ncheck("session.disable_cpu_ep_fallback" in separator, "QNN GPU chưa khóa CPU fallback")\ncheck("musicFromMixMinusVocals" in separator, "HT-Demucs vocals chưa tạo instrumental từ mix trừ vocals")', rel)
write(rel, text)

for rel in ("THIRD_PARTY_NOTICES.md", "app/src/main/assets/third_party_notices.txt"):
    text = read(rel)
    addition = '''

HT-Demucs FT Vocals ONNX model
- Source: https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx
- Pinned revision: 2ef0d757d3e226d0da85fb8c71514f464fcabdd0
- File: htdemucs_ft_vocals_fp16weights.onnx
- License: MIT

ONNX Runtime QNN Execution Provider
- Artifact: com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0
- License: MIT; bundled Qualcomm runtime components retain their accompanying notices.
'''
    if "HT-Demucs FT Vocals ONNX model" not in text:
        text = text.rstrip() + addition + "\n"
    write(rel, text)

for rel in ("scripts/migrate_qnn_gpu.py", ".github/workflows/migrate-qnn-gpu.yml"):
    path = ROOT / rel
    if path.exists():
        path.unlink()

print("QNN GPU migration applied")
