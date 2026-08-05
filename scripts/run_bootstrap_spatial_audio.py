#!/usr/bin/env python3
from __future__ import annotations

import bootstrap_spatial_audio as bootstrap


_original_find_one = bootstrap.find_one


def find_one(root, predicate, label):
    matches = [path for path in root.rglob("*") if path.is_file() and predicate(path)]
    if label == "Steam Audio license" and matches:
        return sorted(matches, key=lambda path: (len(path.relative_to(root).parts), str(path)))[0]
    return _original_find_one(root, predicate, label)


bootstrap.find_one = find_one
raise SystemExit(bootstrap.main())
