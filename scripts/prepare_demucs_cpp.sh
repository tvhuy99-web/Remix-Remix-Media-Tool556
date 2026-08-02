#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/.deps/demucs.cpp"
REV="f1206e9adeea103aef4a636b9e62297cf1f8e34e"

if [[ -d "$DEST/.git" ]] && [[ "$(git -C "$DEST" rev-parse HEAD 2>/dev/null || true)" == "$REV" ]] \
    && [[ -f "$DEST/vendor/eigen/Eigen/Core" ]]; then
    echo "demucs.cpp $REV đã sẵn sàng"
    exit 0
fi

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
git init "$DEST"
git -C "$DEST" remote add origin https://github.com/sevagh/demucs.cpp.git
git -C "$DEST" fetch --depth 1 origin "$REV"
git -C "$DEST" checkout --detach FETCH_HEAD
git -C "$DEST" submodule update --init --depth 1 vendor/eigen

test "$(git -C "$DEST" rev-parse HEAD)" = "$REV"
test -f "$DEST/src/model.hpp"
test -f "$DEST/vendor/eigen/Eigen/Core"
echo "Đã chuẩn bị demucs.cpp $REV"
