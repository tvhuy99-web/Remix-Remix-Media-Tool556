#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/mediatool-core-smoke.jar"
cd "$ROOT"
kotlinc \
  app/src/main/java/com/aistudio/mediatool/core/media/WavHeader.kt \
  app/src/main/java/com/aistudio/mediatool/core/subtitle/SubtitleParser.kt \
  app/src/main/java/com/aistudio/mediatool/core/ml/StemPreflight.kt \
  app/src/main/java/com/aistudio/mediatool/core/ml/StemModelContract.kt \
  app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt \
  app/src/main/java/com/aistudio/mediatool/core/ml/OverlapWindow.kt \
  app/src/main/java/com/aistudio/mediatool/core/ml/OnnxThreadingPolicy.kt \
  app/src/main/java/com/aistudio/mediatool/core/SlideshowTiming.kt \
  app/src/main/java/com/aistudio/mediatool/core/ml/ContentRange.kt \
  app/src/main/java/com/aistudio/mediatool/core/media/AudioMath.kt \
  app/src/main/java/com/aistudio/mediatool/core/media/MediaEffectPolicy.kt \
  app/src/main/java/com/aistudio/mediatool/core/media/TimelineSegments.kt \
  app/src/main/java/com/aistudio/mediatool/core/subtitle/UtteranceQueueTracker.kt \
  app/src/main/java/com/aistudio/mediatool/core/diagnostics/DiagnosticRedactor.kt \
  scripts/CoreSmoke.kt \
  -include-runtime -d "$OUT"
java -jar "$OUT"
rm -f "$OUT"
