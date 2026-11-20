#!/usr/bin/env python3
"""探测 GitHub 镜像可用性：对每个镜像发起 Range 请求，输出可用/失败与耗时。

用法：
    python scripts/probe_mirrors.py                       # 用默认 APK 样本
    python scripts/probe_mirrors.py --url <github 原始链接>
    python scripts/probe_mirrors.py --json                # 输出 JSON（可写成 mirrors.json 的 enabled 字段）
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIRRORS = os.path.join(ROOT, "docs", "mirrors.json")
SAMPLE = (
    "https://github.com/likebe1-1/lan-transfer-android/releases/download/v2.2.0/"
    "LanLink-v2.2.0-arm64-v8a.apk"
)
UA = "LanLink-mirror-probe/1.0"


def load_mirrors():
    with open(MIRRORS, encoding="utf-8") as handle:
        return json.load(handle)


def probe(url, timeout):
    request = urllib.request.Request(
        url,
        headers={"Range": "bytes=0-1023", "User-Agent": UA, "Accept": "*/*"},
        method="GET",
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            data = response.read(2048)
            elapsed = time.time() - started
            ok = status in (200, 206) and len(data) > 0
            return {"ok": ok, "status": status, "ms": round(elapsed * 1000), "bytes": len(data)}
    except Exception as error:  # noqa: BLE001
        elapsed = time.time() - started
        return {"ok": False, "status": 0, "ms": round(elapsed * 1000), "error": str(error)[:120]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=SAMPLE)
    parser.add_argument("--timeout", type=float, default=12.0)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    config = load_mirrors()
    reports = []
    for mirror in config.get("mirrors", []):
        target = (mirror.get("prefix") or "") + args.url
        result = probe(target, args.timeout)
        reports.append({"name": mirror.get("name"), "prefix": mirror.get("prefix"), **result})
        if not args.json:
            flag = "OK " if result["ok"] else "FAIL"
            detail = result.get("error") or f"HTTP {result['status']} {result['bytes']}B"
            print(f"{flag} {mirror.get('name'):<10} {mirror.get('prefix') or '(direct)':<30} {result['ms']:>6} ms  {detail}")

    if args.json:
        print(json.dumps(reports, ensure_ascii=False, indent=2))
        return

    ok = [item for item in reports if item["ok"]]
    print()
    print(f"可用 {len(ok)}/{len(reports)} 个镜像；建议 enabled=true：" + ", ".join(item["name"] or "direct" for item in ok))


if __name__ == "__main__":
    sys.exit(main())
