#!/usr/bin/env python3
"""发布一致性检查：版本号 / update.json / Release APK 哈希。

用法：
    python scripts/check_release_consistency.py
    python scripts/check_release_consistency.py --skip-apk

检查项：
1. app/build.gradle.kts 的 versionCode/versionName；
2. docs/update.json 的 versionCode/versionName 是否与之一致；
3. update.json 的每个 ABI 的 size/sha256 是否与 app/build/outputs/apk/release 下的 APK 匹配（存在时）；
4. update.json 是否带 Ed25519 签名（sig 字段）；
5. 更新源是否为 HTTPS。
"""
import argparse
import hashlib
import json
import os
import re
import sys


def read_gradle_version(path="app/build.gradle.kts"):
    text = open(path, encoding="utf-8").read()
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        raise SystemExit("无法解析 app/build.gradle.kts 的版本号")
    return int(code.group(1)), name.group(1)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 256), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-apk", action="store_true")
    parser.add_argument(
        "--strict-apk",
        action="store_true",
        help="APK size/sha256 与 update.json 不一致时视为错误（发布前使用）；默认仅警告",
    )
    args = parser.parse_args()

    errors = []
    warnings = []
    code, name = read_gradle_version()
    print(f"Gradle: versionCode={code} versionName={name}")

    manifest = json.load(open("docs/update.json", encoding="utf-8"))
    if int(manifest.get("versionCode", -1)) != code:
        errors.append(f"update.json versionCode={manifest.get('versionCode')} 与 Gradle {code} 不一致")
    if manifest.get("versionName") != name:
        errors.append(f"update.json versionName={manifest.get('versionName')} 与 Gradle {name} 不一致")
    if not manifest.get("sig"):
        errors.append("update.json 缺少 Ed25519 签名 sig 字段")
    for apk in manifest.get("apks", []):
        for url in apk.get("urls", []):
            if not url.startswith("https://"):
                errors.append(f"update.json 存在非 HTTPS 下载地址：{url}")

    if not args.skip_apk:
        variant = {
            "arm64-v8a": "app-github-arm64-v8a-release.apk",
            "armeabi-v7a": "app-github-armeabi-v7a-release.apk",
            "universal": "app-github-universal-release.apk",
        }
        release_dir = os.path.join("app", "build", "outputs", "apk", "github", "release")
        for apk in manifest.get("apks", []):
            filename = variant.get(apk.get("abi"))
            if not filename:
                continue
            path = os.path.join(release_dir, filename)
            if not os.path.exists(path):
                print(f"跳过（本地无产物）：{filename}")
                continue
            size = os.path.getsize(path)
            digest = sha256_of(path)
            mismatched = size != apk.get("size") or digest != apk.get("sha256")
            if mismatched:
                message = (
                    f"{filename} size={size} sha256={digest[:16]}… 与 update.json "
                    f"size={apk.get('size')} sha256={str(apk.get('sha256'))[:16]}… 不一致"
                )
                if args.strict_apk:
                    errors.append(message)
                else:
                    warnings.append(message)
            else:
                print(f"OK {filename} size={size} sha256={digest[:16]}…")

    if warnings:
        print("\n警告（本地产物与已发布清单不同，发布前请用 --strict-apk 复核）：")
        for item in warnings:
            print(" - " + item)
    if errors:
        print("\n不一致项：")
        for item in errors:
            print(" - " + item)
        return 1
    print("\n发布一致性检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
