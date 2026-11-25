#!/usr/bin/env python3
"""Two products, two version numbers. One command changes a product's version everywhere, or checks that every place agrees.

    python scripts/bump_version.py web 2.0.0   # SavahBot: dashboard + backend
    python scripts/bump_version.py app 3.0.0   # SavahLive: the Android app (also sets versionCode)
    python scripts/bump_version.py --check     # exit 1 if the places of a product disagree (used by the tests)
    python scripts/bump_version.py --show      # print both versions

web places: frontend/src/version.ts, frontend/public/sw.js, frontend/package.json, backend/app/__init__.py (__version__).
app places: android/app/build.gradle.kts (versionName and versionCode = major*10000 + minor*100 + patch, so 3.0.0 is 30000)
            and backend/app/__init__.py (__savahlive_version__), which is what the server tells phones the newest app version is.
Add the new "## x.y.z" section to CHANGELOG.md yourself, with the product in it ("## SavahBot 2.0.0" or "## SavahLive 3.0.0");
this script tells you if it is missing. Standard library only.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
V = r"(\d+\.\d+\.\d+)"

# product -> list of (file, pattern with the version in group 2)
PLACES = {
    "web": [
        ("frontend/src/version.ts", rf'(APP_VERSION = ")({V[1:-1]})(")'),
        ("frontend/public/sw.js", rf'(const VERSION = ")({V[1:-1]})(";)'),
        ("frontend/package.json", rf'("version": ")({V[1:-1]})(")'),
        ("backend/app/__init__.py", rf'(__version__ = ")({V[1:-1]})(")'),
    ],
    "app": [
        ("android/app/build.gradle.kts", rf'(versionName = ")({V[1:-1]})(")'),
        ("backend/app/__init__.py", rf'(__savahlive_version__ = ")({V[1:-1]})(")'),
    ],
}
CODE = ("android/app/build.gradle.kts", r"(versionCode = )(\d+)()")
NAMES = {"web": "SavahBot", "app": "SavahLive"}


def code_for(version: str) -> int:
    a, b, c = (int(x) for x in version.split("."))
    return a * 10000 + b * 100 + c


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def versions(product: str) -> dict[str, str]:
    out = {}
    for rel, pat in PLACES[product]:
        m = re.search(pat, read(rel))
        out[rel] = m.group(2) if m else "NOT FOUND"
    if product == "app":
        m = re.search(CODE[1], read(CODE[0]))
        out["android versionCode"] = m.group(2) if m else "NOT FOUND"
    return out


def current(product: str) -> str:
    return next(v for k, v in versions(product).items() if k != "android versionCode")


def check() -> list[str]:
    problems = []
    for product in PLACES:
        found = versions(product)
        values = {v for k, v in found.items() if k != "android versionCode"}
        if len(values) != 1 or "NOT FOUND" in values:
            problems.append(f"{NAMES[product]}: the version differs between files: " + ", ".join(f"{k}={v}" for k, v in found.items()))
        elif product == "app" and found["android versionCode"] != str(code_for(next(iter(values)))):
            problems.append(f"android versionCode is {found['android versionCode']} but the version needs {code_for(next(iter(values)))}")
    return problems


def bump(product: str, version: str) -> None:
    if product not in PLACES:
        sys.exit("The product must be web or app")
    if not SEMVER.match(version):
        sys.exit("The version must look like 2.0.0")
    old_version = current(product)
    if tuple(map(int, version.split("."))) <= tuple(map(int, old_version.split("."))):
        sys.exit(f"{version} is not newer than {old_version}. Versions only go up (Android refuses to install an older versionCode).")
    for rel, pat in PLACES[product] + ([CODE] if product == "app" else []):
        text = read(rel)
        new_value = str(code_for(version)) if (rel, pat) == CODE else version
        text, n = re.subn(pat, lambda m: m.group(1) + new_value + m.group(3), text, count=1)
        if n != 1:
            sys.exit(f"Could not find the version in {rel}")
        (ROOT / rel).write_text(text, encoding="utf-8", newline="")
    extra = f" (Android versionCode {code_for(version)})" if product == "app" else ""
    print(f"{NAMES[product]} {old_version} -> {version}{extra}.")
    if f"## {NAMES[product]} {version}" not in read("CHANGELOG.md"):
        print(f'REMINDER: add a "## {NAMES[product]} {version}" section to CHANGELOG.md before you release.')


if __name__ == "__main__":
    a = sys.argv[1:]
    if a == ["--check"]:
        problems = check()
        for p in problems:
            print("-", p)
        print("Versions agree." if not problems else "Versions DISAGREE.")
        sys.exit(1 if problems else 0)
    if a == ["--show"]:
        print(f"SavahBot  (web) {current('web')}\nSavahLive (app) {current('app')}")
        sys.exit(0)
    if len(a) == 2:
        bump(a[0], a[1])
    else:
        sys.exit(__doc__)
