#!/usr/bin/env python3
import time
import json
import urllib.request
import os
import hashlib

RUN_ID = "38573518298"
REPO = "komes2018/Overdrive-release-cn"
TARGET_DIR = "/vol1/@apphome/antigravity-google/byd"
HEADERS = {'User-Agent': 'Mozilla/5.0'}

print(f"Tracking GitHub Actions Run #{RUN_ID} ...")

while True:
    try:
        url = f"https://api.github.com/repos/{REPO}/actions/runs/{RUN_ID}"
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            status = data.get("status")
            conclusion = data.get("conclusion")
            print(f"Status: {status}, Conclusion: {conclusion}")
            if status == "completed":
                if conclusion != "success":
                    print(f"Build failed with conclusion: {conclusion}")
                    exit(1)
                break
    except Exception as e:
        print(f"Error checking status: {e}")
    time.sleep(20)

print("Build succeeded! Checking releases for APK asset...")
time.sleep(5)

# Find release
rel_url = f"https://api.github.com/repos/{REPO}/releases/latest"
req = urllib.request.Request(rel_url, headers=HEADERS)
with urllib.request.urlopen(req) as resp:
    release = json.loads(resp.read().decode())
    tag = release.get("tag_name")
    print(f"Latest release: {tag}")
    apk_asset = None
    for asset in release.get("assets", []):
        if asset.get("name", "").endswith(".apk"):
            apk_asset = asset
            break

if not apk_asset:
    print("Could not find APK asset in release!")
    exit(1)

download_url = apk_asset["browser_download_url"]
apk_name = apk_asset["name"]
target_path = os.path.join(TARGET_DIR, apk_name)

print(f"Downloading {apk_name} from {download_url} to {target_path} ...")
urllib.request.urlretrieve(download_url, target_path)

file_size = os.path.getsize(target_path)
sha256 = hashlib.sha256()
with open(target_path, "rb") as f:
    while chunk := f.read(65536):
        sha256.update(chunk)
digest = sha256.hexdigest()

print(f"Download complete!")
print(f"File: {target_path}")
print(f"Size: {file_size / (1024*1024):.2f} MB ({file_size} bytes)")
print(f"SHA-256: {digest}")
