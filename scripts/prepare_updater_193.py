"""Publish both updater formats, pointing to the immutable, signed 3.30.193 assets."""
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

VERSION = "3.30.193"
VERSION_CODE = 621
BASE = f"https://github.com/tbzmike/smart-s-launcher/releases/download/v{VERSION}/"


def asset(filename):
    path = Path(filename)
    return {
        "apkName": path.name,
        "apkUrl": BASE + path.name,
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


production = asset("SmartSLauncher.apk")
debug = asset("SmartSLauncher-debug.apk")
data = {
    "version": VERSION,
    "versionCode": VERSION_CODE,
    "publishedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "runId": int(os.environ["SOURCE_RUN_ID"]),
    "sha": os.environ["SOURCE_SHA"],
    "variant": "release",
    "production": production,
    "debug": debug,
    # Keep flat fields for installed older updater implementations.
    **production,
    "releaseApkName": production["apkName"],
    "releaseApkUrl": production["apkUrl"],
    "releaseApkSize": production["size"],
    "releaseApkSha256": production["sha256"],
    "debugApkName": debug["apkName"],
    "debugApkUrl": debug["apkUrl"],
    "debugApkSize": debug["size"],
    "debugApkSha256": debug["sha256"],
}
# The installed updater requires these exact fields and a version-specific download URL.
for variant, expected in (("production", "SmartSLauncher.apk"), ("debug", "SmartSLauncher-debug.apk")):
    entry = data[variant]
    assert entry["apkName"] == expected and entry["apkUrl"] == BASE + expected
    assert entry["size"] > 0 and len(entry["sha256"]) == 64
Path("latest-green.json").write_text(json.dumps(data, separators=(",", ":")) + "\n")
