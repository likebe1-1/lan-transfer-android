#!/usr/bin/env python3
"""生成 docs/files.json：GitHub Releases 文件清单（供网站与 App 多镜像下载）。

用法：
    GITHUB_TOKEN=xxx python scripts/gen_file_index.py            # 读取仓库全部 Releases
    python scripts/gen_file_index.py --repo user/repo            # 指定仓库
    python scripts/gen_file_index.py --out docs/files.json

清单结构：
{
  "generatedAt": "...", "repo": "user/repo", "version": 1,
  "files": [
    { "id": "v2.2.0/LanLink-v2.2.0-arm64-v8a.apk", "name": "...", "tag": "v2.2.0",
      "size": 11119049, "sha256": "...", "url": "https://github.com/.../download/v2.2.0/...",
      "downloadCount": 12, "publishedAt": "...", "mirrorable": true }
  ]
}
"""
import argparse
import json
import os
import sys
import urllib.request

DEFAULT_REPO = "likebe1-1/lan-transfer-android"
API = "https://api.github.com"


def gh_get(url, token):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "LanLink-file-index",
            **({"Authorization": "token " + token} if token else {}),
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def sha256_of(asset):
    digest = (asset.get("digest") or "").strip()
    if digest.lower().startswith("sha256:"):
        return digest.split(":", 1)[1].lower()
    return ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--out", default=os.path.join("docs", "files.json"))
    parser.add_argument("--max-releases", type=int, default=100)
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    releases = []
    page = 1
    while len(releases) < args.max_releases:
        batch = gh_get(f"{API}/repos/{args.repo}/releases?per_page=100&page={page}", token)
        if not batch:
            break
        releases.extend(batch)
        page += 1

    files = []
    for release in releases:
        if release.get("draft"):
            continue
        tag = release.get("tag_name") or ""
        published = release.get("published_at") or ""
        for asset in release.get("assets") or []:
            files.append(
                {
                    "id": f"{tag}/{asset['name']}",
                    "name": asset["name"],
                    "tag": tag,
                    "size": int(asset.get("size") or 0),
                    "sha256": sha256_of(asset),
                    "url": asset.get("browser_download_url") or "",
                    "downloadCount": int(asset.get("download_count") or 0),
                    "publishedAt": published,
                    "mirrorable": True,
                }
            )

    files.sort(key=lambda item: (item["publishedAt"], item["name"]), reverse=True)
    payload = {
        "generatedAt": __import__("datetime").datetime.now().astimezone().isoformat(timespec="seconds"),
        "repo": args.repo,
        "version": 1,
        "files": files,
    }
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"wrote {args.out}: {len(files)} files from {len(releases)} releases")


if __name__ == "__main__":
    sys.exit(main())
