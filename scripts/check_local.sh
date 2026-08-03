#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 scripts/verify_project.py
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
