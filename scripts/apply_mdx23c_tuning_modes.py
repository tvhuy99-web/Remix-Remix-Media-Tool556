#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, got {count}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def create_text(path: str, content: str) -> None:
    target = ROOT / path
    if target.exists():
        raise RuntimeError(f"Already exists: {path}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/SettingsManager.kt",
    '''    private const val KEY_STEM_MDX_DENOISE = "stem_mdx_denoise"\n''',
    '''    private const val KEY_STEM_MDX_DENOISE = "stem_mdx_denoise"\n    private const val KEY_STEM_MDX23C_ACCELERATION = "stem_mdx23c_acceleration"\n    private const val KEY_STEM_MDX23C_OVERLAP = "stem_mdx23c_overlap"\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/SettingsManager.kt",
    '''    fun setStemMdxDenoiseEnabled(context: Context, enabled: Boolean) =\n        prefs(context).edit().putBoolean(KEY_STEM_MDX_DENOISE, enabled).apply()\n\n''',
    '''    fun setStemMdxDenoiseEnabled(context: Context, enabled: Boolean) =\n        prefs(context).edit().putBoolean(KEY_STEM_MDX_DENOISE, enabled).apply()\n\n    fun getStemMdx23cAccelerationIndex(context: Context): Int =\n        prefs(context).getInt(KEY_STEM_MDX23C_ACCELERATION, 1).coerceIn(0, 1)\n\n    fun setStemMdx23cAccelerationIndex(context: Context, value: Int) =\n        prefs(context).edit().putInt(KEY_STEM_MDX23C_ACCELERATION, value.coerceIn(0, 1)).apply()\n\n    fun getStemMdx23cOverlapIndex(context: Context): Int =\n        prefs(context).getInt(KEY_STEM_MDX23C_OVERLAP, 1).coerceIn(0, 2)\n\n    fun setStemMdx23cOverlapIndex(context: Context, value: Int) =\n        prefs(context).edit().putInt(KEY_STEM_MDX23C_OVERLAP, value.coerceIn(0, 2)).apply()\n\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt",
    '''        displayName = "MDX23C Vocal HQ",\n        description = "2 stem • dùng cá nhân",\n''',
    '''        displayName = "MDX23C Vocal",\n        description = "2 stem • thử nghiệm",\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/Mdx23cVocalPrototypeContract.kt",
    ''' * periodic-Hann STFT/iSTFT, reflect padding, 75% overlap-add and residual instrumental path in host\n * code so the signal-processing contract remains inspectable and testable.\n''',
    ''' * periodic-Hann STFT/iSTFT, reflect padding, configurable 25/50/75% overlap-add and residual\n * instrumental path in host code so the signal-processing contract remains inspectable and testable.\n''',
)

create_text(
    "app/src/main/java/com/aistudio/mediatool/core/ml/Mdx23cRuntimeTuning.kt",
    '''package com.aistudio.mediatool.core.ml\n\n/** User-selectable execution path for the MDX23C ONNX core. */\nenum class Mdx23cExecutionMode(\n    val settingsIndex: Int,\n    val displayName: String,\n    val acceleration: OnnxAcceleration,\n) {\n    CPU(0, "CPU ổn định", OnnxAcceleration.CPU),\n    XNNPACK(1, "XNNPACK thử nghiệm", OnnxAcceleration.XNNPACK),\n    ;\n\n    companion object {\n        fun fromSettingsIndex(index: Int): Mdx23cExecutionMode =\n            entries.firstOrNull { it.settingsIndex == index } ?: XNNPACK\n    }\n}\n\n/**\n * Runtime overlap modes. The static ONNX tensor is unchanged; only the host-side stride changes.\n * Strides are exact quarters of the 261,120-frame MDX23C chunk.\n */\nenum class Mdx23cOverlapMode(\n    val settingsIndex: Int,\n    val displayName: String,\n    val overlapPercent: Int,\n    val strideFrames: Int,\n    val explanation: String,\n) {\n    FAST(\n        settingsIndex = 0,\n        displayName = "Nhanh • overlap 25%",\n        overlapPercent = 25,\n        strideFrames = 195_840,\n        explanation = "Ít lượt inference nhất; cần nghe kỹ các điểm nối.",\n    ),\n    BALANCED(\n        settingsIndex = 1,\n        displayName = "Cân bằng • overlap 50%",\n        overlapPercent = 50,\n        strideFrames = 130_560,\n        explanation = "Mặc định thử nghiệm; giảm gần một nửa số chunk so với 75%.",\n    ),\n    HIGH_QUALITY(\n        settingsIndex = 2,\n        displayName = "Chất lượng cao • overlap 75%",\n        overlapPercent = 75,\n        strideFrames = 65_280,\n        explanation = "Giữ cấu hình tham chiếu hiện tại nhưng rất chậm.",\n    ),\n    ;\n\n    fun requireCompatible(contract: MdxSpectrogramContract): Mdx23cOverlapMode = apply {\n        require(strideFrames in 1..contract.generatedFrames)\n        require(contract.generatedFrames - strideFrames >= contract.windowFadeFrames) {\n            "Overlap MDX23C phải đủ dài cho cửa sổ fade"\n        }\n    }\n\n    fun overlapFrames(contract: MdxSpectrogramContract): Int =\n        contract.generatedFrames - strideFrames\n\n    companion object {\n        fun fromSettingsIndex(index: Int): Mdx23cOverlapMode =\n            entries.firstOrNull { it.settingsIndex == index } ?: BALANCED\n    }\n}\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    '''import com.aistudio.mediatool.core.ml.DownloadState\nimport com.aistudio.mediatool.core.ml.SeparationState\n''',
    '''import com.aistudio.mediatool.core.ml.DownloadState\nimport com.aistudio.mediatool.core.ml.Mdx23cExecutionMode\nimport com.aistudio.mediatool.core.ml.Mdx23cOverlapMode\nimport com.aistudio.mediatool.core.ml.SeparationState\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    '''    var mdxDenoiseEnabled by rememberSaveable {\n        mutableStateOf(SettingsManager.isStemMdxDenoiseEnabled(context))\n    }\n''',
    '''    var mdxDenoiseEnabled by rememberSaveable {\n        mutableStateOf(SettingsManager.isStemMdxDenoiseEnabled(context))\n    }\n    var mdx23cAccelerationIndex by rememberSaveable {\n        mutableStateOf(SettingsManager.getStemMdx23cAccelerationIndex(context))\n    }\n    var mdx23cOverlapIndex by rememberSaveable {\n        mutableStateOf(SettingsManager.getStemMdx23cOverlapIndex(context))\n    }\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    '''                if (selectedModel.backend == StemInferenceBackend.MDX_LITERT) {\n                    CompactDropdown(\n                        label = "Chất lượng UVR",\n                        values = listOf("Tiêu chuẩn", "Làm sạch kỹ"),\n                        selectedIndex = if (mdxDenoiseEnabled) 1 else 0,\n                        onSelected = { index ->\n                            mdxDenoiseEnabled = index == 1\n                            SettingsManager.setStemMdxDenoiseEnabled(context, mdxDenoiseEnabled)\n                            resetResult()\n                        },\n                        modifier = Modifier.fillMaxWidth(),\n                    )\n                    Text(\n                        if (mdxDenoiseEnabled) {\n                            "Chạy hai lượt đối xứng để giảm nhiễu, thời gian xử lý gần gấp đôi."\n                        } else {\n                            "Một lượt xử lý, nhanh hơn và dùng ít điện hơn."\n                        },\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    )\n                }\n\n''',
    '''                if (selectedModel.backend == StemInferenceBackend.MDX_LITERT) {\n                    CompactDropdown(\n                        label = "Chất lượng UVR",\n                        values = listOf("Tiêu chuẩn", "Làm sạch kỹ"),\n                        selectedIndex = if (mdxDenoiseEnabled) 1 else 0,\n                        onSelected = { index ->\n                            mdxDenoiseEnabled = index == 1\n                            SettingsManager.setStemMdxDenoiseEnabled(context, mdxDenoiseEnabled)\n                            resetResult()\n                        },\n                        modifier = Modifier.fillMaxWidth(),\n                    )\n                    Text(\n                        if (mdxDenoiseEnabled) {\n                            "Chạy hai lượt đối xứng để giảm nhiễu, thời gian xử lý gần gấp đôi."\n                        } else {\n                            "Một lượt xử lý, nhanh hơn và dùng ít điện hơn."\n                        },\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    )\n                }\n\n                if (selectedModel.id == StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID) {\n                    CompactDropdown(\n                        label = "Tăng tốc MDX23C",\n                        values = Mdx23cExecutionMode.entries.map { it.displayName },\n                        selectedIndex = mdx23cAccelerationIndex,\n                        onSelected = { index ->\n                            mdx23cAccelerationIndex = index\n                            SettingsManager.setStemMdx23cAccelerationIndex(context, index)\n                            resetResult()\n                        },\n                        modifier = Modifier.fillMaxWidth(),\n                    )\n                    CompactDropdown(\n                        label = "Tốc độ và chất lượng",\n                        values = Mdx23cOverlapMode.entries.map { it.displayName },\n                        selectedIndex = mdx23cOverlapIndex,\n                        onSelected = { index ->\n                            mdx23cOverlapIndex = index\n                            SettingsManager.setStemMdx23cOverlapIndex(context, index)\n                            resetResult()\n                        },\n                        modifier = Modifier.fillMaxWidth(),\n                    )\n                    Text(\n                        Mdx23cOverlapMode.fromSettingsIndex(mdx23cOverlapIndex).explanation +\n                            " XNNPACK sẽ tự trở về CPU nếu không tương thích.",\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    )\n                    Text(\n                        "LiteRT GPU và Qualcomm QNN chưa bật trong bản này vì cần artifact/runtime riêng.",\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    )\n                }\n\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    '''    StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID -> "MDX23C Vocal HQ"\n''',
    '''    StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID -> "MDX23C Vocal"\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '''        val denoiseEnabled = contract.supportsPolarityDenoise &&\n            SettingsManager.isStemMdxDenoiseEnabled(context)\n        var seamFrames: List<Long> = emptyList()\n\n''',
    '''        val denoiseEnabled = contract.supportsPolarityDenoise &&\n            SettingsManager.isStemMdxDenoiseEnabled(context)\n        val isMdx23c = model.id == StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID\n        val mdx23cExecutionMode = if (isMdx23c) {\n            Mdx23cExecutionMode.fromSettingsIndex(\n                SettingsManager.getStemMdx23cAccelerationIndex(context),\n            )\n        } else null\n        val mdx23cOverlapMode = if (isMdx23c) {\n            Mdx23cOverlapMode.fromSettingsIndex(\n                SettingsManager.getStemMdx23cOverlapIndex(context),\n            ).requireCompatible(contract)\n        } else null\n        val configuredOnnxAcceleration = mdx23cExecutionMode?.acceleration\n            ?: OnnxAcceleration.fromSettingsIndex(SettingsManager.getHardwareAccelIndex(context))\n        val runtimeStrideFrames = mdx23cOverlapMode?.strideFrames ?: contract.strideFrames\n        val runtimeOverlapFrames = contract.generatedFrames - runtimeStrideFrames\n        var seamFrames: List<Long> = emptyList()\n\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '''                "stride_frames" to contract.strideFrames,\n                "overlap_frames" to contract.overlapFrames,\n                "window_fade_frames" to contract.windowFadeFrames,\n''',
    '''                "stride_frames" to runtimeStrideFrames,\n                "overlap_frames" to runtimeOverlapFrames,\n                "overlap_mode" to (mdx23cOverlapMode?.name ?: "MODEL_DEFAULT"),\n                "overlap_percent" to mdx23cOverlapMode?.overlapPercent,\n                "requested_acceleration" to configuredOnnxAcceleration,\n                "window_fade_frames" to contract.windowFadeFrames,\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '''                    configuredAcceleration = OnnxAcceleration.fromSettingsIndex(\n                        SettingsManager.getHardwareAccelIndex(context),\n                    ),\n''',
    '''                    configuredAcceleration = configuredOnnxAcceleration,\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '''                        "effective_backend" to engine.backendLabel,\n                        "cpu_threads" to SettingsManager.getNumThreads(context),\n''',
    '''                        "effective_backend" to engine.backendLabel,\n                        "requested_acceleration" to configuredOnnxAcceleration,\n                        "overlap_mode" to (mdx23cOverlapMode?.name ?: "MODEL_DEFAULT"),\n                        "stride_frames" to runtimeStrideFrames,\n                        "cpu_threads" to SettingsManager.getNumThreads(context),\n''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '''                val generatedFrames = contract.generatedFrames\n                val strideFrames = contract.strideFrames\n''',
    '''                val generatedFrames = contract.generatedFrames\n                val strideFrames = runtimeStrideFrames\n''',
)

create_text(
    "app/src/test/java/com/aistudio/mediatool/core/ml/Mdx23cRuntimeTuningTest.kt",
    '''package com.aistudio.mediatool.core.ml\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n\nclass Mdx23cRuntimeTuningTest {\n    private val contract = Mdx23cVocalPrototypeContract.spectrogram\n\n    @Test\n    fun executionDefaultsToXnnpackAndAllowsCpu() {\n        assertEquals(\n            Mdx23cExecutionMode.XNNPACK,\n            Mdx23cExecutionMode.fromSettingsIndex(99),\n        )\n        assertEquals(OnnxAcceleration.CPU, Mdx23cExecutionMode.CPU.acceleration)\n        assertEquals(OnnxAcceleration.XNNPACK, Mdx23cExecutionMode.XNNPACK.acceleration)\n    }\n\n    @Test\n    fun overlapModesUseExactQuarterChunkStrides() {\n        assertEquals(195_840, Mdx23cOverlapMode.FAST.requireCompatible(contract).strideFrames)\n        assertEquals(130_560, Mdx23cOverlapMode.BALANCED.requireCompatible(contract).strideFrames)\n        assertEquals(65_280, Mdx23cOverlapMode.HIGH_QUALITY.requireCompatible(contract).strideFrames)\n        assertEquals(65_280, Mdx23cOverlapMode.FAST.overlapFrames(contract))\n        assertEquals(130_560, Mdx23cOverlapMode.BALANCED.overlapFrames(contract))\n        assertEquals(195_840, Mdx23cOverlapMode.HIGH_QUALITY.overlapFrames(contract))\n    }\n\n    @Test\n    fun unknownOverlapSettingFallsBackToBalanced() {\n        assertEquals(Mdx23cOverlapMode.BALANCED, Mdx23cOverlapMode.fromSettingsIndex(-1))\n        assertEquals(Mdx23cOverlapMode.BALANCED, Mdx23cOverlapMode.fromSettingsIndex(99))\n    }\n}\n''',
)

create_text(
    "docs/MDX23C_PUBLIC_MODEL_HOSTING.md",
    '''# MDX23C public model hosting\n\nThe application repository is private, so unauthenticated Android downloads of its release assets\nreturn HTTP 404. The final slim APK should download the pinned ONNX file from a separate public\nrepository owned by the same personal GitHub account.\n\n## Recommended layout\n\n1. Create a public repository such as `tvhuy99-web/MediaTool-Personal-Models`.\n2. Create release tag `mdx23c-vocal-personal-v1`.\n3. Upload `mdx23c-vocals-core.onnx` as a release asset, not as a Git history blob.\n4. Verify the asset is exactly 448,152,790 bytes and SHA-256 is\n   `8925ece1f0da006d342856f93e75ba2dea9058d44c286c4cd6a98a41c67367bb`.\n5. Open the download URL in a private browser window and confirm it returns the file without login.\n6. Update `StemModelRegistry.mdx23cVocalPersonal.modelSpec.url` to the public release URL.\n7. Remove `BundledModelInstaller`, the bundled APK workflow and ONNX asset packaging.\n\nKeep the private application repository private. Only the model-host repository needs to be public.\n''',
)

print("MDX23C tuning modes prepared")
