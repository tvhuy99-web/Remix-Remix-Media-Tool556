#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    '''        val configuredAcceleration = OnnxAcceleration.fromSettingsIndex(
            SettingsManager.getHardwareAccelIndex(context),
        )
        val conservativeMemoryMode = model.id == StemModelRegistry.MEL_BAND_ROFORMER_ID
        val requestedAcceleration = when {
            conservativeMemoryMode -> OnnxAcceleration.CPU
            configuredAcceleration in model.allowedAccelerators -> configuredAcceleration
            else -> OnnxAcceleration.CPU
        }.also { selected ->
            if (selected != configuredAcceleration) {
                DiagnosticLogger.warn(
                    component = TAG,
                    event = if (conservativeMemoryMode) "provider_forced_for_memory" else "provider_not_allowed",
                    sessionId = taskId,
                    fields = mapOf(
                        "model_id" to model.id,
                        "requested_provider" to configuredAcceleration,
                        "fallback_provider" to selected,
                    ),
                )
            }
        }
''',
    '''        val configuredAcceleration = OnnxAcceleration.fromSettingsIndex(
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
            val changed = selected != configuredAcceleration
            if (changed) {
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
''',
)

replace_once(
    "scripts/verify_project.py",
    'check("session.disable_cpu_ep_fallback" in separator, "QNN GPU chưa khóa CPU fallback")',
    'check("session.disable_cpu_ep_fallback" in separator, "QNN GPU chưa khóa CPU fallback")\ncheck("provider_forced_by_model" in separator and "HTDEMUCS_FT_VOCALS_QNN_ID" in separator, "Model QNN chưa bắt buộc dùng QNN GPU")',
)

for rel in ("scripts/force_qnn_model_backend.py", ".github/workflows/force-qnn-model-backend.yml"):
    path = ROOT / rel
    if path.exists():
        path.unlink()

print("Model-specific QNN backend applied")
