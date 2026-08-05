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


replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    '''private fun shortModelName(model: StemModelDescriptor): String = when (model.id) {
    StemModelRegistry.UVR_MDX_VOC_FT_LITERT_ID -> "UVR MDX-Net"
    else -> "Demucs"
}
''',
    '''private fun shortModelName(model: StemModelDescriptor): String = when (model.id) {
    StemModelRegistry.UVR_MDX_VOC_FT_LITERT_ID -> "UVR MDX-Net"
    StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID -> "MDX23C Vocal HQ"
    else -> model.displayName
}
''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemViewModel.kt",
    '''    private val downloader = ModelDownloader(appContext)
''',
    '''    private val downloader = ModelDownloader(appContext)
    private val bundledInstaller = BundledModelInstaller(appContext)
''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemViewModel.kt",
    '''import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.ModelDownloader
''',
    '''import com.aistudio.mediatool.core.ml.BundledModelInstaller
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.ModelDownloader
''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemViewModel.kt",
    '''            downloader.downloadModel(model.modelSpec, model.id).collect { state ->
                if (_selectedModel.value.id == model.id) _downloadState.value = state
            }
''',
    '''            val states = if (model.id == StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID) {
                bundledInstaller.install(
                    spec = model.modelSpec,
                    assetPath = MDX23C_BUNDLED_ASSET,
                    modelId = model.id,
                )
            } else {
                downloader.downloadModel(model.modelSpec, model.id)
            }
            states.collect { state ->
                if (_selectedModel.value.id == model.id) _downloadState.value = state
            }
''',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemViewModel.kt",
    '''    companion object {
        private const val REMOVED_MEL_BAND_PREFIX = "melband-roformer-kj-vocals-"
    }
''',
    '''    companion object {
        private const val REMOVED_MEL_BAND_PREFIX = "melband-roformer-kj-vocals-"
        private const val MDX23C_BUNDLED_ASSET = "models/mdx23c-vocals-core.onnx"
    }
''',
)

replace_once(
    "app/build.gradle.kts",
    '''        noCompress += "tflite"
''',
    '''        noCompress += setOf("tflite", "onnx")
''',
)

installer = ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/BundledModelInstaller.kt"
if installer.exists():
    raise RuntimeError(f"Already exists: {installer}")
installer.write_text('''package com.aistudio.mediatool.core.ml

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Installs a large personal-use model shipped inside a special APK build. */
class BundledModelInstaller(private val context: Context) {
    private val modelDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun install(
        spec: ModelSpec,
        assetPath: String,
        modelId: String,
    ): Flow<DownloadState> = flow {
        val sessionId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val destination = File(modelDir, spec.fileName)
        val partial = File(modelDir, spec.fileName + ".part")

        try {
            if (validate(destination, spec)) {
                emit(DownloadState.Success(destination))
                return@flow
            }
            destination.delete()
            partial.delete()

            val available = StatFs(modelDir.absolutePath).availableBytes
            val required = spec.expectedBytes + STORAGE_HEADROOM_BYTES
            require(available >= required) {
                "Không đủ dung lượng để cài model. Cần thêm khoảng ${(required - available) / MIB} MB"
            }

            val asset = try {
                context.assets.open(assetPath)
            } catch (_: FileNotFoundException) {
                throw IOException(
                    "Bản APK này không chứa MDX23C Vocal HQ. Hãy cài đúng bản APK MDX23C bundled.",
                )
            }

            DiagnosticLogger.info(
                component = TAG,
                event = "bundled_install_start",
                sessionId = sessionId,
                fields = mapOf(
                    "model_id" to modelId,
                    "asset_path" to assetPath,
                    "expected_bytes" to spec.expectedBytes,
                    "available_storage_bytes" to available,
                ),
            )

            emit(DownloadState.Downloading(0f))
            asset.buffered(BUFFER_SIZE).use { input ->
                partial.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    var lastProgressAt = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > spec.expectedBytes) {
                            throw IOException("Model đóng gói lớn hơn dung lượng đã ghim")
                        }
                        output.write(buffer, 0, read)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= 250L) {
                            lastProgressAt = now
                            emit(DownloadState.Downloading(progress(copied, spec.expectedBytes)))
                        }
                    }
                    output.flush()
                }
            }

            if (!validate(partial, spec)) {
                partial.delete()
                throw IOException("Model đóng gói không vượt qua kiểm tra dung lượng/SHA-256")
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            if (!validate(destination, spec)) {
                destination.delete()
                throw IOException("Model sau khi cài không vượt qua kiểm tra SHA-256")
            }

            DiagnosticLogger.info(
                component = TAG,
                event = "bundled_install_success",
                sessionId = sessionId,
                fields = mapOf(
                    "model_id" to modelId,
                    "bytes" to destination.length(),
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            emit(DownloadState.Downloading(1f))
            emit(DownloadState.Success(destination))
        } catch (cancelled: CancellationException) {
            DiagnosticLogger.info(
                component = TAG,
                event = "bundled_install_cancelled",
                sessionId = sessionId,
                fields = mapOf("model_id" to modelId, "partial_bytes" to partial.length()),
            )
            throw cancelled
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = TAG,
                event = "bundled_install_failed",
                sessionId = sessionId,
                message = error.message,
                fields = mapOf("model_id" to modelId, "partial_bytes" to partial.length()),
                error = error,
            )
            emit(DownloadState.Error(error.message ?: "Không thể cài model đóng gói"))
        }
    }.flowOn(Dispatchers.IO)

    private fun validate(file: File, spec: ModelSpec): Boolean =
        file.isFile && file.length() == spec.expectedBytes &&
            runCatching { sha256(file).equals(spec.sha256, ignoreCase = true) }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun progress(bytes: Long, total: Long): Float =
        if (total <= 0L) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)

    private companion object {
        const val TAG = "BundledModelInstaller"
        const val BUFFER_SIZE = 1024 * 1024
        const val MIB = 1024L * 1024L
        const val STORAGE_HEADROOM_BYTES = 128L * MIB
    }
}
''', encoding="utf-8")

workflow = ROOT / ".github/workflows/build-mdx23c-bundled-apk.yml"
workflow.parent.mkdir(parents=True, exist_ok=True)
workflow.write_text('''name: Build MDX23C bundled APK

on:
  push:
    branches:
      - agent/mdx23c-vocal-onnx
    paths:
      - app/**
      - .github/workflows/build-mdx23c-bundled-apk.yml

permissions:
  contents: read

concurrency:
  group: build-mdx23c-bundled-apk
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 90
    steps:
      - name: Checkout source
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle cache
        uses: gradle/actions/setup-gradle@v4

      - name: Restore stable CI signing key
        run: |
          mkdir -p .ci
          keytool -genkeypair -v \\
            -keystore .ci/mediatool-ci-debug.keystore \\
            -storepass android \\
            -alias androiddebugkey \\
            -keypass android \\
            -dname "CN=MediaTool CI,O=MediaTool,C=US" \\
            -keyalg RSA -keysize 2048 -validity 10000

      - name: Download private MDX23C release asset
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          mkdir -p app/src/main/assets/models
          gh release download mdx23c-vocal-personal-v1 \\
            --pattern mdx23c-vocals-core.onnx \\
            --dir app/src/main/assets/models
          echo "8925ece1f0da006d342856f93e75ba2dea9058d44c286c4cd6a98a41c67367bb  app/src/main/assets/models/mdx23c-vocals-core.onnx" \\
            | sha256sum --check --strict
          test "$(stat -c %s app/src/main/assets/models/mdx23c-vocals-core.onnx)" = "448152790"

      - name: Verify, test and build bundled APK
        run: |
          python3 scripts/verify_project.py
          ./gradlew testDebugUnitTest assembleDebug --stacktrace

      - name: Verify model is inside APK
        run: |
          APK=app/build/outputs/apk/debug/app-debug.apk
          unzip -l "$APK" | grep -F "assets/models/mdx23c-vocals-core.onnx"
          test "$(unzip -p "$APK" assets/models/mdx23c-vocals-core.onnx | sha256sum | cut -d' ' -f1)" = \\
            "8925ece1f0da006d342856f93e75ba2dea9058d44c286c4cd6a98a41c67367bb"

      - name: Upload bundled APK
        uses: actions/upload-artifact@v4
        with:
          name: MediaTool-MDX23C-bundled-debug
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 30
          compression-level: 0
''', encoding="utf-8")

print("MDX23C bundled fix prepared")
