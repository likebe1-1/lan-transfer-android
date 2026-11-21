#!/usr/bin/env python3
"""生成 update.json 签名用的 Ed25519 密钥对（依赖 openssl）。

用法：
    python scripts/gen_update_signing_key.py --out-dir .update-signing

生成：
    .update-signing/update_signing_private.pem  PKCS#8 私钥（离线保管，切勿提交）
    .update-signing/update_signing_public.b64   公钥 DER Base64（填入 UpdateManifestVerifier.PUBLIC_KEY_BASE64）
"""
import argparse
import os
import shutil
import subprocess
import sys


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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", default=".update-signing")
    args = parser.parse_args()
    openssl = find_openssl()
    if openssl is None:
        print("未找到 openssl，请安装 Git for Windows 后重试", file=sys.stderr)
        return 1
    os.makedirs(args.out_dir, exist_ok=True)
    private_pem = os.path.join(args.out_dir, "update_signing_private.pem")
    subprocess.run([openssl, "genpkey", "-algorithm", "ED25519", "-out", private_pem], check=True, capture_output=True)
    public_pem = subprocess.run(
        [openssl, "pkey", "-in", private_pem, "-pubout"], check=True, capture_output=True
    ).stdout.decode()
    body = "".join(line.strip() for line in public_pem.splitlines() if not line.startswith("-----"))
    with open(os.path.join(args.out_dir, "update_signing_public.b64"), "w", encoding="utf-8") as handle:
        handle.write(body + "\n")
    print("私钥：" + private_pem + "（离线保管，切勿提交）")
    print("公钥（Base64 DER）：")
    print(body)
    print()
    print("下一步：把公钥填入 UpdateManifestVerifier.PUBLIC_KEY_BASE64，再执行")
    print("  python scripts/sign_update_manifest.py --key " + private_pem)
    return 0


if __name__ == "__main__":
    sys.exit(main())
