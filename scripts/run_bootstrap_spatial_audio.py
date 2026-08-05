#!/usr/bin/env python3
from __future__ import annotations

import bootstrap_spatial_audio as bootstrap


_original_find_one = bootstrap.find_one


def find_one(root, predicate, label):
    if label == "Steam Audio license":
        pinned = bootstrap.ROOT / "third_party/steam_audio/LICENSE.txt"
        if not pinned.is_file() or pinned.stat().st_size == 0:
            raise RuntimeError("Thiếu giấy phép Apache 2.0 đã ghim cho Steam Audio")
        return pinned
    return _original_find_one(root, predicate, label)


bootstrap.find_one = find_one
raise SystemExit(bootstrap.main())
