#!/usr/bin/env python3
"""把 GitHub Releases 的文件同步到 Gitee 仓库（镜像发布）。

用法：
    GITEE_TOKEN=xxx python scripts/sync_gitee.py                 # 同步推荐架构文件（默认 3 个）
    GITEE_TOKEN=xxx python scripts/sync_gitee.py --tag v2.2.0    # 只同步指定版本
    GITEE_TOKEN=xxx python scripts/sync_gitee.py --all           # 同步所有 Release 的所有文件
    GITEE_TOKEN=xxx python scripts/sync_gitee.py --files a.apk,b.apk

环境变量：
    GITEE_TOKEN   Gitee 私人令牌（projects 权限）
    GITHUB_TOKEN  可选，用于读取 GitHub 资产 digest（未提供时用 GitHub 下载后本地计算 SHA-256）

流程：
    1. 读取 GitHub Releases（及 docs/files.json 作为兜底清单）；
    2. 对每个 Gitee 版本（Release）先查是否已存在，不存在则创建；
    3. 上传缺失的附件（Gitee 单文件 ≤ 100MB）；
    4. 生成 docs/gitee/files.json（Gitee 直链 + size + sha256）。
"""
import argparse
import hashlib
import json
import os
import sys
import shutil
import tempfile
import urllib.parse
import urllib.request

GITHUB_REPO = "likebe1-1/lan-transfer-android"
GITEE_REPO = "likebe1-1/lanlink-android"
GITHUB_API = "https://api.github.com"
GITEE_API = "https://gitee.com/api/v5"
MAX_ATTACHMENT = 100 * 1024 * 1024  # Gitee 免费版单文件 100MB


def http_json(url, token=None, method="GET", payload=None):
    data = None
    headers = {"Accept": "application/json", "User-Agent": "LanLink-gitee-sync"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read()
        return json.loads(body) if body else None


def download(url, target):
    request = urllib.request.Request(url, headers={"User-Agent": "LanLink-gitee-sync"})
    with urllib.request.urlopen(request, timeout=120) as response, open(target, "wb") as handle:
        while True:
            chunk = response.read(1024 * 256)
            if not chunk:
                break
            handle.write(chunk)
    return target


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 256), b""):
            digest.update(chunk)
    return digest.hexdigest()


def upload_attachment(release_id, token, path, name):
    boundary = "----LanLinkBoundary" + hashlib.md5(name.encode()).hexdigest()
    with open(path, "rb") as handle:
        content = handle.read()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="access_token"\r\n\r\n{token}\r\n'
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
    url = f"{GITEE_API}/repos/{GITEE_REPO}/releases/{release_id}/attach_files"
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "User-Agent": "LanLink-gitee-sync",
        },
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="同步所有 Release 的所有文件")
    parser.add_argument("--tag", default="", help="只同步指定版本")
    parser.add_argument("--files", default="", help="只同步指定文件名（逗号分隔）")
    parser.add_argument("--out", default=os.path.join("docs", "gitee", "files.json"))
    parser.add_argument(
        "--assets-dir",
        default="",
        help="本地资产目录：存在同名文件时直接使用，避免重复从 GitHub 下载（网络不稳时很有用）",
    )
    args = parser.parse_args()

    token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token:
        # 兼容：从用户目录读取（项目外，绝不入库）
        token_file = os.path.join(os.path.expanduser("~"), ".lanlink", "gitee_token")
        if os.path.exists(token_file):
            token = open(token_file, encoding="utf-8").read().strip()
    if not token:
        print("缺少 GITEE_TOKEN（环境变量或 ~/.lanlink/gitee_token）", file=sys.stderr)
        return 1
    github_token = os.environ.get("GITHUB_TOKEN", "").strip()

    releases = []
    page = 1
    while True:
        batch = http_json(
            f"{GITHUB_API}/repos/{GITHUB_REPO}/releases?per_page=100&page={page}",
            token=github_token or None,
        )
        if not batch:
            break
        releases.extend(batch)
        page += 1

    wanted_names = {name.strip() for name in args.files.split(",") if name.strip()}
    selected = []
    for release in releases:
        if release.get("draft"):
            continue
        tag = release.get("tag_name") or ""
        if args.tag and tag != args.tag:
            continue
        for asset in release.get("assets") or []:
            name = asset["name"]
            if not args.all and not wanted_names and not any(
                key in name for key in ("arm64-v8a", "armeabi-v7a", "universal")
            ):
                continue
            if wanted_names and name not in wanted_names:
                continue
            selected.append(
                {
                    "tag": tag,
                    "name": name,
                    "size": int(asset.get("size") or 0),
                    "url": asset["browser_download_url"],
                    "publishedAt": release.get("published_at") or "",
                    "body": release.get("body") or "",
                }
            )

    print(f"待同步 {len(selected)} 个文件")

    existing_releases = {item["tag_name"]: item for item in http_json(f"{GITEE_API}/repos/{GITEE_REPO}/releases?per_page=100", token)}
    created = []

    for item in selected:
        tag = item["tag"]
        release = existing_releases.get(tag)
        if release is None:
            release = http_json(
                f"{GITEE_API}/repos/{GITEE_REPO}/releases",
                token,
                method="POST",
                payload={
                    "tag_name": tag,
                    "name": f"LanLink {tag}",
                    "body": item["body"][:20000] or f"LanLink {tag} 发布文件（GitHub Releases 镜像）",
                    "target_commitish": "master",
                    "prerelease": False,
                },
            )
            existing_releases[tag] = release
            print(f"创建 Gitee 发行版 {tag}")

        attached = {asset["name"] for asset in (release.get("assets") or [])}
        if item["name"] in attached:
            print(f"跳过（已存在）{tag}/{item['name']}")
            continue
        if item["size"] > MAX_ATTACHMENT:
            print(f"跳过（超过 Gitee 100MB 限制）{tag}/{item['name']} {item['size']}")
            continue

        with tempfile.TemporaryDirectory() as temp_dir:
            path = os.path.join(temp_dir, item["name"])
            local = os.path.join(args.assets_dir, item["name"]) if args.assets_dir else ""
            if local and os.path.exists(local):
                print(f"使用本地资产 {item['name']} …")
                shutil.copyfile(local, path)
            else:
                print(f"下载 {item['name']} …")
                download(item["url"], path)
            digest = sha256_file(path)
            print(f"上传 {tag}/{item['name']}（{len(open(path,'rb').read())} B）…")
            upload_attachment(release["id"], token, path, item["name"])
            created.append({**item, "sha256": digest})

    # 重新读取 Gitee 发行版，生成清单
    gitee_releases = http_json(f"{GITEE_API}/repos/{GITEE_REPO}/releases?per_page=100", token)
    files = []
    for release in gitee_releases:
        tag = release.get("tag_name") or ""
        published = release.get("created_at") or release.get("updated_at") or ""
        for asset in release.get("assets") or []:
            files.append(
                {
                    "id": f"{tag}/{asset['name']}",
                    "name": asset["name"],
                    "tag": tag,
                    "size": int(asset.get("size") or 0),
                    "sha256": asset.get("sha256") or "",
                    "url": asset.get("browser_download_url") or "",
                    "downloadCount": 0,
                    "publishedAt": published,
                    "mirrorable": False,
                }
            )
    files.sort(key=lambda entry: (entry["publishedAt"], entry["name"]), reverse=True)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(
            {
                "generatedAt": __import__("datetime").datetime.now().astimezone().isoformat(timespec="seconds"),
                "repo": GITEE_REPO,
                "version": 1,
                "primaryHost": "gitee",
                "files": files,
            },
            handle,
            ensure_ascii=False,
            indent=2,
        )
        handle.write("\n")
    print(f"写入 {args.out}：{len(files)} 个文件，本次新增 {len(created)} 个")
    return 0


if __name__ == "__main__":
    sys.exit(main())
