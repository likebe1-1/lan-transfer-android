#!/usr/bin/env python3
"""给 docs/update.json 追加/更新 Ed25519 签名（sig 字段）。

依赖：openssl（Windows 上通常随 Git 提供）或 cryptography 库，二选一。

用法：
    python scripts/sign_update_manifest.py --key .update-signing/update_signing_private.pem --in docs/update.json
"""
import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from canonical_manifest import canonical  # noqa: E402


def find_openssl():
    candidate = shutil.which("openssl")
    if candidate:
        return candidate
    for path in (
        r"C:\Program Files\Git\usr\bin\openssl.exe",
        r"C:\Program Files\Git\mingw64\bin\openssl.exe",
    ):
        if os.path.exists(path):
            return path
    return None





def sign_with_openssl(openssl, key_path, payload):
    with tempfile.TemporaryDirectory() as temp:
        data_path = os.path.join(temp, "payload.txt")
        sig_path = os.path.join(temp, "payload.sig")
        with open(data_path, "wb") as handle:
            handle.write(payload.encode("utf-8"))
        subprocess.run(
            [openssl, "pkeyutl", "-sign", "-inkey", key_path, "-rawin", "-in", data_path, "-out", sig_path],
            check=True,
            capture_output=True,
        )
        with open(sig_path, "rb") as handle:
            return base64.b64encode(handle.read()).decode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--key", required=True, help="Ed25519 私钥（PEM）")
    parser.add_argument("--in", dest="input", default="docs/update.json")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    with open(args.input, encoding="utf-8") as handle:
        data = json.load(handle)
    payload = canonical(data)

    openssl = find_openssl()
    if openssl is None:
        print("未找到 openssl，请安装 Git for Windows 或 pip install cryptography", file=sys.stderr)
        return 1
    signature = sign_with_openssl(openssl, args.key, payload)
    data["sig"] = signature

    target = args.out or args.input
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"已签名并写入 {target}")
    print("公钥（用于填入 App）：")
    subprocess.run([openssl, "pkey", "-in", args.key, "-pubout"], check=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
