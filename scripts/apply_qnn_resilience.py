#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match, found {count}")
    path.write_text(updated, encoding="utf-8")


audio_separator = ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt"
new_open_session = r'''    private fun openSession(env: OrtEnvironment): OpenedSession {
        val configuredAcceleration = OnnxAcceleration.fromSettingsIndex(
            SettingsManager.getHardwareAccelIndex(context),
        )
        val conservativeMemoryMode = model.id == StemModelRegistry.MEL_BAND_ROFORMER_ID
        val modelForcedAcceleration = when (model.id) {
            StemModelRegistry.HTDEMUCS_FT_VOCALS_QNN_ID -> OnnxAcceleration.QNN_GPU
            else -> null
        }
        val effectiveAcceleration = modelForcedAcceleration ?: configuredAcceleration
        val requestedAcceleration = when {
            conservativeMemoryMode -> OnnxAcceleration.CPU
            effectiveAcceleration in model.allowedAccelerators -> effectiveAcceleration
            else -> OnnxAcceleration.CPU
        }.also { selected ->
            if (selected != configuredAcceleration) {
                val event = when {
                    conservativeMemoryMode -> "provider_forced_for_memory"
                    modelForcedAcceleration != null -> "provider_forced_by_model"
                    else -> "provider_not_allowed"
                }
                DiagnosticLogger.warn(
                    component = TAG,
                    event = event,
                    sessionId = taskId,
                    fields = mapOf(
                        "model_id" to model.id,
                        "configured_provider" to configuredAcceleration,
                        "model_forced_provider" to modelForcedAcceleration,
                        "effective_provider" to selected,
                    ),
                )
            }
        }

        // QNN GPU vẫn được ưu tiên cho model FP16. CPU EP xử lý các node mà QNN
        // không nhận trong cùng session. Nếu bản thân session QNN không mở được
        // trên thiết bị, thử XNNPACK trước khi rơi về CPU thuần.
        val providerChain = mutableListOf(requestedAcceleration).apply {
            if (
                requestedAcceleration == OnnxAcceleration.QNN_GPU &&
                OnnxAcceleration.XNNPACK in model.allowedAccelerators
            ) {
                add(OnnxAcceleration.XNNPACK)
            }
            if (OnnxAcceleration.CPU !in this) add(OnnxAcceleration.CPU)
        }.distinct()
        val failures = mutableListOf<String>()

        for ((attemptIndex, candidate) in providerChain.withIndex()) {
            val options = try {
                createSessionOptions(candidate.settingsIndex)
            } catch (error: Exception) {
                val detail = "${candidate.name}/config: ${error.message ?: error::class.java.simpleName}"
                failures += detail
                DiagnosticLogger.warn(
                    component = TAG,
                    event = "onnx_provider_attempt_failed",
                    sessionId = taskId,
                    message = error.message,
                    fields = mapOf(
                        "model_id" to model.id,
                        "requested_provider" to requestedAcceleration,
                        "attempted_provider" to candidate,
                        "attempt_index" to attemptIndex,
                        "failure_stage" to "configure",
                        "next_provider" to providerChain.getOrNull(attemptIndex + 1),
                    ),
                    error = error,
                )
                continue
            }

            try {
                val session = env.createSession(modelFile.absolutePath, options)
                logInfo(
                    event = "onnx_session_opened",
                    fields = mapOf(
                        "model_id" to model.id,
                        "requested_provider" to requestedAcceleration,
                        "effective_provider" to candidate,
                        "attempt_index" to attemptIndex,
                        "provider_chain" to providerChain.joinToString("->"),
                        "cpu_ep_fallback_enabled" to (candidate == OnnxAcceleration.QNN_GPU),
                    ),
                )
                return OpenedSession(
                    options = options,
                    session = session,
                    provider = candidate,
                )
            } catch (error: Exception) {
                options.close()
                val detail = "${candidate.name}/session: ${error.message ?: error::class.java.simpleName}"
                failures += detail
                DiagnosticLogger.warn(
                    component = TAG,
                    event = "onnx_provider_attempt_failed",
                    sessionId = taskId,
                    message = error.message,
                    fields = mapOf(
                        "model_id" to model.id,
                        "requested_provider" to requestedAcceleration,
                        "attempted_provider" to candidate,
                        "attempt_index" to attemptIndex,
                        "failure_stage" to "create_session",
                        "next_provider" to providerChain.getOrNull(attemptIndex + 1),
                    ),
                    error = error,
                )
            }
        }

        throw IllegalStateException(
            "Không thể mở model ${model.displayName} bằng chuỗi tăng tốc " +
                "${providerChain.joinToString(" -> ")}. ${failures.joinToString(" | ")}",
        )
    }

    suspend fun separate'''
replace_regex_once(
    audio_separator,
    r"    private fun openSession\(env: OrtEnvironment\): OpenedSession \{.*?\n    \}\n\n    suspend fun separate",
    new_open_session,
    "AudioSeparator.openSession",
)

settings = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/SettingsScreen.kt"
replace_once(
    settings,
    'val hwList = listOf("CPU", "NNAPI", "XNNPACK", "QNN GPU (Snapdragon, thử nghiệm)")',
    'val hwList = listOf("CPU", "NNAPI", "XNNPACK", "QNN GPU + fallback thông minh (Snapdragon)")',
    "hardware acceleration label",
)
replace_once(
    settings,
    'Text("QNN GPU không fallback CPU: graph không tương thích sẽ báo lỗi thay vì chạy chậm âm thầm.")',
    'Text("Ưu tiên QNN GPU; node chưa hỗ trợ dùng CPU EP. Nếu session QNN không mở được, ứng dụng thử XNNPACK rồi CPU.")',
    "QNN supporting text",
)
replace_once(
    settings,
    'if (hwAccelIndex == 3) Text("Không ảnh hưởng đến inference QNN GPU.")',
    'if (hwAccelIndex == 3) Text("Số luồng được dùng cho node CPU fallback và phương án XNNPACK dự phòng.")',
    "thread supporting text",
)

registry = ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt"
replace_once(
    registry,
    'description = "Model vocals fine-tuned, ưu tiên GPU Snapdragon; instrumental được lấy từ mix trừ vocals.",',
    'description = "Model vocals fine-tuned; ưu tiên QNN GPU, tự thử XNNPACK/CPU khi thiết bị không mở được session QNN.",',
    "HT-Demucs description",
)

verify = ROOT / "scripts/verify_project.py"
verify_text = verify.read_text(encoding="utf-8")
anchor = 'check(\'"cpu_fallback_disabled" to false\' in separator, "Log QNN chưa xác nhận CPU fallback được bật")\n'
addition = (
    anchor
    + 'check("onnx_provider_attempt_failed" in separator, "AudioSeparator thiếu log từng lần fallback provider")\n'
    + 'check("provider_chain" in separator and "OnnxAcceleration.XNNPACK" in separator, "QNN thiếu chuỗi dự phòng XNNPACK/CPU")\n'
    + 'check("CPU fallback đã bị khóa" not in separator, "AudioSeparator còn thông báo sai rằng CPU fallback bị khóa")\n'
)
if verify_text.count(anchor) != 1:
    raise SystemExit("verify_project QNN anchor mismatch")
verify_text = verify_text.replace(anchor, addition, 1)
settings_anchor = 'check("DiagnosticReportCard" in settings_screen, "Cài đặt thiếu nút xuất nhật ký")\n'
settings_addition = (
    settings_anchor
    + 'check("không fallback CPU" not in settings_screen, "Cài đặt còn mô tả sai chính sách QNN fallback")\n'
    + 'check("fallback thông minh" in settings_screen, "Cài đặt chưa mô tả chuỗi fallback QNN")\n'
)
if verify_text.count(settings_anchor) != 1:
    raise SystemExit("verify_project settings anchor mismatch")
verify.write_text(verify_text.replace(settings_anchor, settings_addition, 1), encoding="utf-8")

print("Adaptive QNN fallback patch applied")
