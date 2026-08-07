from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


config = "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioConfig.kt"
replace_once(
    config,
    "    val stereoMode: SpatialStereoMode = SpatialStereoMode.MID_SIDE,\n",
    "    val stereoMode: SpatialStereoMode = SpatialStereoMode.MID_SIDE,\n"
    "    val hissProtection: SpatialHissProtection = SpatialHissProtection.AUTO,\n",
)
replace_once(
    config,
    '            "stereo_mode" to value.stereoMode.name,\n',
    '            "stereo_mode" to value.stereoMode.name,\n'
    '            "hiss_protection" to value.hissProtection.name,\n',
)

engine = "app/src/main/java/com/aistudio/mediatool/core/spatial/SpatialAudioEngine.kt"
replace_once(
    engine,
    "            val decodedPcm = withContext(Dispatchers.IO) { PcmStereoAnalyzer.analyze(decoded) }\n"
    "            val inputLoudness = analyzeLoudness(\n",
    "            val decodedPcm = withContext(Dispatchers.IO) { PcmStereoAnalyzer.analyze(decoded) }\n"
    "            val hissProfile = if (\n"
    "                value.spatialBlend > BYPASS_EPSILON &&\n"
    "                value.hissProtection != SpatialHissProtection.OFF\n"
    "            ) {\n"
    "                withContext(Dispatchers.IO) { SpatialHissProtector.analyze(decoded) }\n"
    "            } else {\n"
    "                SpatialHissProfile()\n"
    "            }\n"
    "            val hissPlan = SpatialHissProtector.plan(value.hissProtection, hissProfile)\n"
    "            val inputLoudness = analyzeLoudness(\n",
)
replace_once(
    engine,
    "                    inputLoudness.diagnosticFields(\"input\") +\n"
    "                    decodedPcm.diagnosticFields(\"decoded_pcm\") + mapOf(\n",
    "                    inputLoudness.diagnosticFields(\"input\") +\n"
    "                    decodedPcm.diagnosticFields(\"decoded_pcm\") +\n"
    "                    hissProfile.diagnosticFields() +\n"
    "                    hissPlan.diagnosticFields() + mapOf(\n",
)
replace_once(
    engine,
    "                config = value,\n"
    "                workDir = workDir,\n",
    "                config = value,\n"
    "                hissPlan = hissPlan,\n"
    "                workDir = workDir,\n",
)
replace_once(
    engine,
    "                    renderedPcm.diagnosticFields(\"final_pcm\") +\n"
    "                    nativeBefore + nativeAfter + mapOf(\n",
    "                    renderedPcm.diagnosticFields(\"final_pcm\") +\n"
    "                    hissProfile.diagnosticFields() +\n"
    "                    hissPlan.diagnosticFields() +\n"
    "                    nativeBefore + nativeAfter + mapOf(\n",
)
replace_once(
    engine,
    "                    renderedPcm.diagnosticFields(\"final_pcm\") +\n"
    "                    outputInfo.diagnosticFields(\"output\") +\n",
    "                    renderedPcm.diagnosticFields(\"final_pcm\") +\n"
    "                    hissProfile.diagnosticFields() +\n"
    "                    hissPlan.diagnosticFields() +\n"
    "                    outputInfo.diagnosticFields(\"output\") +\n",
)
replace_once(
    engine,
    "                    decodedPcm.diagnosticFields(\"decoded_pcm\") +\n"
    "                    renderedPcm.diagnosticFields(\"final_pcm\") + qualityDelta + mapOf(\n",
    "                    decodedPcm.diagnosticFields(\"decoded_pcm\") +\n"
    "                    renderedPcm.diagnosticFields(\"final_pcm\") +\n"
    "                    hissProfile.diagnosticFields() +\n"
    "                    hissPlan.diagnosticFields() + qualityDelta + mapOf(\n",
)
replace_once(
    engine,
    "        config: SpatialAudioConfig,\n"
    "        workDir: File,\n",
    "        config: SpatialAudioConfig,\n"
    "        hissPlan: SpatialHissPlan,\n"
    "        workDir: File,\n",
)
replace_once(
    engine,
    "        val fullySpatial = config.copy(spatialBlend = 1f).normalized()\n"
    "        val spatialComposite = File(workDir, \"spatial_composite.f32\")\n",
    "        val spatialInput = prepareSpatialInput(\n"
    "            decoded = decoded,\n"
    "            workDir = workDir,\n"
    "            hissPlan = hissPlan,\n"
    "            expectedDurationMs = expectedDurationMs,\n"
    "            onProgress = onProgress,\n"
    "        )\n"
    "        val fullySpatial = SpatialHissProtector.protectConfig(\n"
    "            config.copy(spatialBlend = 1f),\n"
    "            hissPlan,\n"
    "        )\n"
    "        val spatialComposite = File(workDir, \"spatial_composite.f32\")\n",
)
replace_once(
    engine,
    "            SpatialStereoMode.SHARED_POSITION -> {\n"
    "                onProgress(42f, \"Render stereo cùng một vị trí\")\n"
    "                val native = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(decoded, spatialComposite, fullySpatial)\n"
    "                }\n"
    "                native.copy(stereoMode = \"shared_position\")\n"
    "            }\n",
    "            SpatialStereoMode.SHARED_POSITION -> {\n"
    "                val sharedRendered = File(workDir, \"shared_position_rendered.f32\")\n"
    "                onProgress(42f, \"Render stereo cùng một vị trí\")\n"
    "                val native = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(spatialInput, sharedRendered, fullySpatial)\n"
    "                }\n"
    "                protectWetBranch(\n"
    "                    input = sharedRendered,\n"
    "                    output = spatialComposite,\n"
    "                    hissPlan = hissPlan,\n"
    "                    phase = \"spatial_shared_hiss_protection\",\n"
    "                    startPercent = 58f,\n"
    "                    endPercent = 63f,\n"
    "                    expectedDurationMs = expectedDurationMs,\n"
    "                    onProgress = onProgress,\n"
    "                )\n"
    "                native.copy(stereoMode = \"shared_position\")\n"
    "            }\n",
)
replace_once(
    engine,
    "                val midInput = File(workDir, \"mid_input.f32\")\n"
    "                val midRendered = File(workDir, \"mid_rendered.f32\")\n",
    "                val midInput = File(workDir, \"mid_input.f32\")\n"
    "                val midRendered = File(workDir, \"mid_rendered.f32\")\n"
    "                val midProtected = File(workDir, \"mid_rendered_protected.f32\")\n",
)
replace_once(
    engine,
    "                    input = decoded,\n"
    "                    output = midInput,\n"
    "                    filter = \"pan=stereo|c0=0.5*FL+0.5*FR|c1=0.5*FL+0.5*FR\",\n",
    "                    input = spatialInput,\n"
    "                    output = midInput,\n"
    "                    filter = \"pan=stereo|c0=0.5*FL+0.5*FR|c1=0.5*FL+0.5*FR\",\n",
)
replace_once(
    engine,
    "                val native = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(midInput, midRendered, fullySpatial)\n"
    "                }\n"
    "                runRawPcmComposite(\n"
    "                    inputs = listOf(decoded, midRendered),\n",
    "                val native = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(midInput, midRendered, fullySpatial)\n"
    "                }\n"
    "                protectWetBranch(\n"
    "                    input = midRendered,\n"
    "                    output = midProtected,\n"
    "                    hissPlan = hissPlan,\n"
    "                    phase = \"spatial_mid_hiss_protection\",\n"
    "                    startPercent = 53f,\n"
    "                    endPercent = 56f,\n"
    "                    expectedDurationMs = expectedDurationMs,\n"
    "                    onProgress = onProgress,\n"
    "                )\n"
    "                runRawPcmComposite(\n"
    "                    inputs = listOf(decoded, midProtected),\n",
)
replace_once(
    engine,
    "                val leftRendered = File(workDir, \"left_object_rendered.f32\")\n"
    "                val rightRendered = File(workDir, \"right_object_rendered.f32\")\n",
    "                val leftRendered = File(workDir, \"left_object_rendered.f32\")\n"
    "                val rightRendered = File(workDir, \"right_object_rendered.f32\")\n"
    "                val leftProtected = File(workDir, \"left_object_protected.f32\")\n"
    "                val rightProtected = File(workDir, \"right_object_protected.f32\")\n",
)
replace_once(
    engine,
    "                    input = decoded,\n"
    "                    output = leftInput,\n"
    "                    filter = \"pan=stereo|c0=FL|c1=0*FR\",\n",
    "                    input = spatialInput,\n"
    "                    output = leftInput,\n"
    "                    filter = \"pan=stereo|c0=FL|c1=0*FR\",\n",
)
replace_once(
    engine,
    "                    input = decoded,\n"
    "                    output = rightInput,\n"
    "                    filter = \"pan=stereo|c0=0*FL|c1=FR\",\n",
    "                    input = spatialInput,\n"
    "                    output = rightInput,\n"
    "                    filter = \"pan=stereo|c0=0*FL|c1=FR\",\n",
)
replace_once(
    engine,
    "                val leftMetrics = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(leftInput, leftRendered, leftConfig)\n"
    "                }\n"
    "                onProgress(57f, \"Render object R lệch +${formatAngle(offset)}°\")\n",
    "                val leftMetrics = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(leftInput, leftRendered, leftConfig)\n"
    "                }\n"
    "                protectWetBranch(\n"
    "                    input = leftRendered,\n"
    "                    output = leftProtected,\n"
    "                    hissPlan = hissPlan,\n"
    "                    phase = \"spatial_left_hiss_protection\",\n"
    "                    startPercent = 54f,\n"
    "                    endPercent = 56f,\n"
    "                    expectedDurationMs = expectedDurationMs,\n"
    "                    onProgress = onProgress,\n"
    "                )\n"
    "                onProgress(57f, \"Render object R lệch +${formatAngle(offset)}°\")\n",
)
replace_once(
    engine,
    "                val rightMetrics = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(rightInput, rightRendered, rightConfig)\n"
    "                }\n"
    "                runRawPcmComposite(\n"
    "                    inputs = listOf(leftRendered, rightRendered),\n",
    "                val rightMetrics = withContext(Dispatchers.Default) {\n"
    "                    SteamAudioBridge.render(rightInput, rightRendered, rightConfig)\n"
    "                }\n"
    "                protectWetBranch(\n"
    "                    input = rightRendered,\n"
    "                    output = rightProtected,\n"
    "                    hissPlan = hissPlan,\n"
    "                    phase = \"spatial_right_hiss_protection\",\n"
    "                    startPercent = 61f,\n"
    "                    endPercent = 63f,\n"
    "                    expectedDurationMs = expectedDurationMs,\n"
    "                    onProgress = onProgress,\n"
    "                )\n"
    "                runRawPcmComposite(\n"
    "                    inputs = listOf(leftProtected, rightProtected),\n",
)
replace_once(
    engine,
    "    private suspend fun blendOriginalAndSpatial(\n",
    "    private suspend fun prepareSpatialInput(\n"
    "        decoded: File,\n"
    "        workDir: File,\n"
    "        hissPlan: SpatialHissPlan,\n"
    "        expectedDurationMs: Long,\n"
    "        onProgress: suspend (Float, String) -> Unit,\n"
    "    ): File {\n"
    "        val filter = SpatialHissProtector.spatialInputFilter(hissPlan) ?: return decoded\n"
    "        val output = File(workDir, \"spatial_input_protected.f32\")\n"
    "        runRawPcmFilter(\n"
    "            input = decoded,\n"
    "            output = output,\n"
    "            filter = filter,\n"
    "            phase = \"spatial_input_hiss_protection\",\n"
    "            startPercent = 36f,\n"
    "            endPercent = 39f,\n"
    "            expectedDurationMs = expectedDurationMs,\n"
    "            onProgress = onProgress,\n"
    "        )\n"
    "        require(output.length() == decoded.length()) {\n"
    "            \"Bù latency bảo vệ tiếng xì làm thay đổi độ dài PCM\"\n"
    "        }\n"
    "        return output\n"
    "    }\n\n"
    "    private suspend fun protectWetBranch(\n"
    "        input: File,\n"
    "        output: File,\n"
    "        hissPlan: SpatialHissPlan,\n"
    "        phase: String,\n"
    "        startPercent: Float,\n"
    "        endPercent: Float,\n"
    "        expectedDurationMs: Long,\n"
    "        onProgress: suspend (Float, String) -> Unit,\n"
    "    ) {\n"
    "        val filter = SpatialHissProtector.wetBranchFilter(hissPlan)\n"
    "        if (filter == null) {\n"
    "            withContext(Dispatchers.IO) { input.copyTo(output, overwrite = true) }\n"
    "            onProgress(endPercent, \"Bảo vệ tiếng xì đang tắt\")\n"
    "            return\n"
    "        }\n"
    "        runRawPcmFilter(\n"
    "            input = input,\n"
    "            output = output,\n"
    "            filter = filter,\n"
    "            phase = phase,\n"
    "            startPercent = startPercent,\n"
    "            endPercent = endPercent,\n"
    "            expectedDurationMs = expectedDurationMs,\n"
    "            onProgress = onProgress,\n"
    "        )\n"
    "    }\n\n"
    "    private suspend fun blendOriginalAndSpatial(\n",
)

print("Spatial hiss protection integration applied")
