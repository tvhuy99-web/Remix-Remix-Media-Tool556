#!/usr/bin/env python3
from pathlib import Path

# Guarded one-shot compiler fix for the current stem hardening branch.
path = Path("app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt")
text = path.read_text(encoding="utf-8")

old_start = '''            if (!is4StemMode) {
                StemPcmToolkit.createResidual(
'''
new_start = '''            val activeJob = coroutineContext[kotlinx.coroutines.Job]
            if (!is4StemMode) {
                StemPcmToolkit.createResidual(
'''
old_cancel = '''                    cancellationCheck = { coroutineContext.ensureActive() },
'''
new_cancel = '''                    cancellationCheck = {
                        if (activeJob?.isActive == false) {
                            throw CancellationException("Đã hủy xử lý")
                        }
                    },
'''

for old, new, label in (
    (old_start, new_start, "active job capture"),
    (old_cancel, new_cancel, "synchronous cancellation check"),
):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} anchor, found {count}")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Demucs residual cancellation compile fix applied")
