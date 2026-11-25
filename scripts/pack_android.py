#!/usr/bin/env python3
"""Packs only the android/ folder into downloads/SavahLive-android-source.zip (the README links to it).

    python scripts/pack_android.py

Leaves out build output, caches, IDE files and your local.properties / keystore, so the zip is clean and safe to share.
Run it again after changing the app (the file name stays the same, so the README link never goes stale). Standard library only.
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {"build", ".gradle", ".idea", ".cxx", "captures", "__pycache__"}
SKIP_FILES = {"local.properties", "keystore.properties"}
SKIP_SUFFIX = {".jks", ".keystore", ".apk", ".aab", ".iml"}


def main() -> None:
    android = ROOT / "android"
    out = ROOT / "downloads" / "SavahLive-android-source.zip"
    out.parent.mkdir(exist_ok=True)
    version = re.search(r'versionName = "([\d.]+)"', (android / "app" / "build.gradle.kts").read_text(encoding="utf-8"))
    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(android.rglob("*")):
            rel = f.relative_to(ROOT)
            if f.is_dir() or SKIP_DIRS & set(rel.parts) or f.name in SKIP_FILES or f.suffix in SKIP_SUFFIX:
                continue
            z.write(f, rel.as_posix())
            count += 1
    print(f"SavahLive {version.group(1) if version else '?'}: {count} files, {out.stat().st_size // 1024} KB -> {out.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
