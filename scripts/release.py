#!/usr/bin/env python3
"""LanLink 一键发布脚本（默认 dry-run，绝不会真正发布）。

只做「可自动化的机械步骤」：版本号递增 → Release 构建 → 收集 APK（size/sha256）
→ 更新并签名 docs/update.json → 可选同步 Gitee → 发布一致性自检 → 打印人工步骤。
创建 GitHub Release、上传资产、站点部署、公告等仍需人工完成（脚本只打印清单）。

安全默认：
    - 默认 dry-run：只打印计划、将写的内容摘要与将执行的命令清单；
      不修改任何文件、不构建、不上传、不同步。
    - 必须显式 --execute 才会真正执行。
    - 不使用 --execute 时即使加 --bump 也绝不写回 build.gradle.kts。

用法：
    python scripts/release.py --help
    python scripts/release.py                       # dry-run 预览（不改版本）
    python scripts/release.py --bump patch          # dry-run 预览 patch 递增
    python scripts/release.py --bump minor --execute --skip-gitee
    python scripts/release.py --execute --skip-build --skip-gitee   # 仅重建清单+签名+自检
    python scripts/release.py --execute --skip-build                # 含 Gitee 同步

参数：
    --bump {patch,minor}  递增 app/build.gradle.kts 的 versionCode(+1) 与 versionName
    --dry-run             dry-run（默认，可显式写出；与 --execute 互斥）
    --execute             真正修改文件/构建/签名/同步
    --skip-build          跳过 assembleRelease，直接使用现有 APK 产物
    --skip-gitee          跳过 Gitee 镜像同步（GitHub Release 尚未发布时使用）
    --changelog TEXT      可选：覆盖 update.json 的 changelog 正文；
                          脚本会自动保证首行为「LanLink vX.Y.Z：…」

环境变量（均不会被打印）：
    GITEE_TOKEN   Gitee 私人令牌（也可放 ~/.lanlink/gitee_token）；
                  缺失时脚本跳过 Gitee 同步并提示，不视为失败。
    GITHUB_TOKEN  可选，供 sync_gitee.py 读取 GitHub 资产 digest。

退出码：0 成功；1 已知错误（中文可读原因）；130 用户中断。
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# 允许以 python scripts/release.py 方式运行时复用同目录模块；同时避免生成 __pycache__（保持 git 工作区干净）
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))

from bump_version import (  # noqa: E402
    VersionError,
    plan_bump,
    read_gradle_version,
    write_gradle_text,
)

GITHUB_REPO = "likebe1-1/lan-transfer-android"
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
MANIFEST_FILE = ROOT / "docs" / "update.json"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
STAGING_ROOT = ROOT / "app" / "build" / "outputs" / "release-assets"
SIGNING_KEY = ROOT / ".update-signing" / "update_signing_private.pem"

# 只发布这三个 ABI（与 docs/update.json、App 端下载逻辑一致）；x86 / x86_64 产物忽略
ABI_ORDER = ("arm64-v8a", "armeabi-v7a", "universal")
MANAGED_MANIFEST_KEYS = ("versionCode", "versionName", "changelog", "releasePageUrl", "apks", "sig")

DEFAULT_BUILD_TASK = ":app:assembleRelease"


class ReleaseError(Exception):
    """发布流程中的已知错误；消息面向用户，中文可读。"""


# --------------------------------------------------------------------------- 基础工具


def rel_path(path) -> str:
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 256), b""):
            digest.update(chunk)
    return digest.hexdigest()


def human_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024
    return f"{size} B"


def asset_name(version: str, abi: str) -> str:
    return f"LanLink-v{version}-{abi}.apk"


def download_url(version: str, abi: str) -> str:
    return (
        f"https://github.com/{GITHUB_REPO}/releases/download/"
        f"v{version}/{asset_name(version, abi)}"
    )


def release_page_url(version: str) -> str:
    return f"https://github.com/{GITHUB_REPO}/releases/tag/v{version}"


def format_command(args) -> str:
    parts = []
    for index, arg in enumerate(args):
        if index == 0 and arg == sys.executable:
            arg = "python"
        parts.append(f'"{arg}"' if " " in arg else arg)
    return " ".join(parts)


def build_command():
    if os.name == "nt":
        return ["cmd", "/c", "scripts\\gw.cmd", DEFAULT_BUILD_TASK]
    return ["./gradlew", DEFAULT_BUILD_TASK]


def sign_command():
    return [
        sys.executable,
        "scripts/sign_update_manifest.py",
        "--key",
        ".update-signing/update_signing_private.pem",
        "--in",
        "docs/update.json",
    ]


def gitee_command(version: str):
    return [sys.executable, "scripts/sync_gitee.py", "--tag", f"v{version}"]


def consistency_command():
    return [sys.executable, "scripts/check_release_consistency.py", "--strict-apk"]


def atomic_write_text(path: Path, text: str) -> None:
    """先写临时文件再 os.replace，避免中途失败留下半截文件。"""
    temp = path.with_name(path.name + ".tmp")
    try:
        with open(temp, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        os.replace(temp, path)
    except OSError as error:
        try:
            temp.unlink()
        except OSError:
            pass
        raise ReleaseError(f"写入 {rel_path(path)} 失败：{error}") from error


# --------------------------------------------------------------------------- 版本与清单


def load_manifest() -> dict:
    if not MANIFEST_FILE.is_file():
        raise ReleaseError(f"找不到 {rel_path(MANIFEST_FILE)}，请在仓库根目录运行")
    try:
        with open(MANIFEST_FILE, encoding="utf-8") as handle:
            data = json.load(handle)
    except ValueError as error:
        raise ReleaseError(f"{rel_path(MANIFEST_FILE)} 不是合法 JSON：{error}") from error
    except OSError as error:
        raise ReleaseError(f"读取 {rel_path(MANIFEST_FILE)} 失败：{error}") from error
    if not isinstance(data, dict):
        raise ReleaseError(f"{rel_path(MANIFEST_FILE)} 顶层必须是 JSON 对象")
    return data


def update_changelog(existing: str, version: str, override=None) -> str:
    """保留 changelog 正文，只把首行替换成「LanLink vX.Y.Z：…」。

    - 未提供 --changelog 时沿用现有正文（多行时只改首行的版本前缀）；
    - 提供 --changelog 时以它为准，并自动补上首行版本前缀。
    """
    text = (override if override is not None else (existing or "")).strip("\n")
    lines = text.split("\n") if text else [""]
    first = lines[0]
    match = re.match(r"^(LanLink\s+v)([0-9]+(?:\.[0-9]+)*)(\s*[:：])?(.*)$", first)
    if match:
        first = f"{match.group(1)}{version}{match.group(3) or '：'}{match.group(4)}"
    else:
        first = f"LanLink v{version}：{first}" if first else f"LanLink v{version}："
    return "\n".join([first] + lines[1:])


def build_manifest(existing: dict, version: str, code: int, changelog: str, apks: dict, allow_missing: bool) -> dict:
    """构造新的 update.json（保留未管理字段；sig 移除，稍后由签名脚本重建）。"""
    data = copy.deepcopy(existing)
    data["versionCode"] = code
    data["versionName"] = version
    data["changelog"] = changelog
    data["releasePageUrl"] = release_page_url(version)
    entries = []
    for abi in ABI_ORDER:
        info = apks.get(abi)
        if info is None:
            if allow_missing:
                entries.append({"abi": abi, "size": None, "sha256": None, "urls": [download_url(version, abi)]})
            continue
        entries.append(
            {
                "abi": abi,
                "size": info["size"],
                "sha256": info["sha256"],
                "urls": [download_url(version, abi)],
            }
        )
    data["apks"] = entries
    data.pop("sig", None)
    return data


def manifest_text(data: dict) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


# --------------------------------------------------------------------------- APK 产物


def collect_apks():
    """扫描 app/build/outputs/apk/release 下的 Release APK。

    返回 (found, ignored)：found 为 {abi: {path,name,size,sha256}}，ignored 为不发布的文件名。
    排除 unsigned APK；只保留 ABI_ORDER 中的三个 ABI。
    """
    found = {}
    ignored = []
    if not APK_DIR.is_dir():
        return found, ignored
    for path in sorted(APK_DIR.glob("*.apk")):
        lower = path.name.lower()
        if "unsigned" in lower:
            ignored.append(path.name)
            continue
        abi = next((item for item in ABI_ORDER if f"-{item}-" in lower), None)
        if abi is None:
            ignored.append(path.name)
            continue
        if abi in found:
            raise ReleaseError(
                f"发现多个 {abi} APK（{found[abi]['name']} / {path.name}），"
                f"无法确定发布哪个；请先清理 {rel_path(APK_DIR)}"
            )
        found[abi] = {
            "path": path,
            "name": path.name,
            "size": path.stat().st_size,
            "sha256": sha256_file(path),
        }
    return found, ignored


def read_output_metadata():
    """读取 Gradle 的 output-metadata.json，用于判断现有 APK 的真实版本号。"""
    metadata = APK_DIR / "output-metadata.json"
    if not metadata.is_file():
        return None
    try:
        with open(metadata, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return None
    versions = set()
    for element in data.get("elements") or []:
        if not isinstance(element, dict):
            continue
        versions.add((int(element.get("versionCode") or -1), str(element.get("versionName") or "")))
    return versions


def ensure_apk_version(code: int, version: str, apks: dict) -> None:
    """execute 时校验本地产物版本号，避免 --skip-build 误用旧 APK。"""
    versions = read_output_metadata()
    if versions and (code, version) not in versions:
        found = "、".join(f"versionCode={c}/versionName={n}" for c, n in sorted(versions)) or "未知"
        raise ReleaseError(
            f"本地产物版本（{found}）与目标 versionCode={code} versionName={version} 不一致。"
            "请去掉 --skip-build 重新构建（或用 --bump 递增后再构建）。"
        )
    expected = {abi: f"app-{abi}-release.apk" for abi in ABI_ORDER}
    mismatched = [abi for abi, filename in expected.items() if abi in apks and apks[abi]["name"] != filename]
    if mismatched:
        raise ReleaseError(f"APK 文件名不符合 Gradle 拆分约定：{', '.join(mismatched)}；请重新构建")


def stage_assets(apks: dict, version: str):
    """把 3 个 APK 复制为 GitHub Release 资产名，便于人工上传。"""
    staging_dir = STAGING_ROOT / f"v{version}"
    try:
        staging_dir.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise ReleaseError(f"创建发布资产目录 {rel_path(staging_dir)} 失败：{error}") from error
    staged = []
    for abi in ABI_ORDER:
        info = apks[abi]
        target = staging_dir / asset_name(version, abi)
        try:
            shutil.copy2(info["path"], target)
        except OSError as error:
            raise ReleaseError(f"复制 {info['name']} → {rel_path(target)} 失败：{error}") from error
        if target.stat().st_size != info["size"] or sha256_file(target) != info["sha256"]:
            raise ReleaseError(f"复制后的资产 {rel_path(target)} 与源 APK 不一致（size/sha256），已停止")
        staged.append(target)
        print(f"  ✔ {rel_path(target)}（{human_size(info['size'])}）")
    return staging_dir, staged


# --------------------------------------------------------------------------- Gitee / 输出辅助


def gitee_token_source():
    """检测 Gitee token 是否可用，返回来源描述（绝不返回 token 本身）。"""
    if os.environ.get("GITEE_TOKEN", "").strip():
        return "环境变量 GITEE_TOKEN"
    token_file = Path.home() / ".lanlink" / "gitee_token"
    try:
        if token_file.is_file() and token_file.read_text(encoding="utf-8").strip():
            return f"文件 {token_file}"
    except OSError:
        return None
    return None


def run_step(args, label: str, hint: str = "") -> None:
    """执行外部命令；非 0 退出码立即抛 ReleaseError（不吞异常）。"""
    print(f"\n▶ {label}：{format_command(args)}")
    try:
        result = subprocess.run(args, cwd=str(ROOT))
    except FileNotFoundError as error:
        raise ReleaseError(f"{label}失败：找不到可执行命令（{error}）。{hint}".strip()) from error
    except OSError as error:
        raise ReleaseError(f"{label}启动失败：{error}。{hint}".strip()) from error
    if result.returncode != 0:
        suffix = f" 提示：{hint}" if hint else ""
        raise ReleaseError(f"{label}失败（退出码 {result.returncode}），已立即停止。{suffix}".strip())
    print(f"✔ {label}完成")


def print_section(number: int, total: int, title: str) -> None:
    print(f"\n[{number}/{total}] {title}")


def print_apk_table(apks: dict) -> None:
    if not apks:
        print("  未发现现有 Release APK（构建后才会产生）。")
        return
    print(f"  {'ABI':<14}{'产物文件':<32}{'大小':>10}  sha256")
    print("  " + "-" * 78)
    for abi in ABI_ORDER:
        info = apks.get(abi)
        if info is None:
            print(f"  {abi:<14}{'(缺失)':<32}")
            continue
        print(f"  {abi:<14}{info['name']:<32}{human_size(info['size']):>10}  {info['sha256'][:16]}…")


def print_manifest_summary(data: dict) -> None:
    changelog = data.get("changelog") or ""
    lines = changelog.split("\n") if changelog else [""]
    print(f"  versionCode    ：{data.get('versionCode')}")
    print(f"  versionName    ：{data.get('versionName')}")
    print(f"  changelog 首行 ：{lines[0]}")
    if len(lines) > 1:
        print(f"  changelog      ：其余 {len(lines) - 1} 行保持不变")
    print(f"  releasePageUrl ：{data.get('releasePageUrl')}")
    print(f"  apks           ：{len(data.get('apks') or [])} 个（arm64-v8a / armeabi-v7a / universal）")
    for apk in data.get("apks") or []:
        abi = apk.get("abi")
        if apk.get("size") is None:
            print(f"    - {abi:<14} size/sha256 构建后计算  url={apk['urls'][0]}")
        else:
            print(f"    - {abi:<14} size={apk.get('size')}  sha256={str(apk.get('sha256'))[:16]}…")
            print(f"      url={apk['urls'][0]}")
    preserved = [key for key in data if key not in MANAGED_MANIFEST_KEYS]
    if preserved:
        print(f"  保留字段       ：{', '.join(preserved)}（不修改）")
    print("  sig            ：执行时由签名脚本重新生成（Ed25519）")


def print_commands(commands) -> None:
    print("\n将要执行的命令清单（按顺序）：")
    for index, (label, args) in enumerate(commands, start=1):
        print(f"  {index}. {label}：{format_command(args)}")


def print_manual_steps(version: str, staged_dir, gitee_done: bool) -> None:
    staging = rel_path(staged_dir) if staged_dir is not None else f"app/build/outputs/release-assets/v{version}"
    print("\n后续人工步骤（脚本不会自动执行）：")
    print("  1. 检查改动（脚本不执行 git commit）：")
    print("       git status")
    print("       git diff app/build.gradle.kts docs/update.json")
    print(f"  2. 在 GitHub 创建 Release：tag v{version}（目标 master），")
    print(f"     上传 {staging}/ 下的 3 个 APK（LanLink-v{version}-<abi>.apk）；")
    print(f"     说明参考 docs/RELEASE_NOTES_v{version}.md（如尚未创建需先补写）。")
    if gitee_done:
        print(f"  3. Gitee 已自动同步：确认 https://gitee.com/likebe1-1/lanlink-android/releases 已有 v{version} 及 3 个资产。")
    else:
        print("  3. Gitee 同步（GitHub Release 发布后执行）：")
        print(f"       python scripts\\sync_gitee.py --tag v{version}")
        print("     或重新运行：python scripts\\release.py --execute --skip-build --skip-gitee")
    print("  4. 更新站点（docs/files.json、镜像探测、部署）：")
    print("       python scripts\\gen_file_index.py")
    print("       python scripts\\probe_mirrors.py")
    print("       部署 docs/ 到 GitHub Pages / Gitee Pages，并确认线上 update.json 与本地一致。")
    print(f"  5. 发公告：docs/ANNOUNCEMENT_v{version}.md、群公告等。")
    print("  6. 发布后验证：")
    print("       python scripts\\check_release_consistency.py --strict-apk")
    print(f"       拉取 GitHub raw docs/update.json 与 Gitee raw 清单，确认 versionName=v{version} 且 sig 有效；")
    print("       在真机 App 内点「检查更新」验证下载与 SHA-256 校验。")


# --------------------------------------------------------------------------- 主流程


def _run(args, execute: bool, state: dict) -> int:
    total = 6
    mode = "EXECUTE（会真正修改文件/构建/同步）" if execute else "DRY-RUN（默认，不会修改任何文件、不构建、不上传）"
    print("=" * 78)
    print(f" LanLink 一键发布 · {mode}")
    print("=" * 78)
    print(f"仓库根目录：{ROOT}")

    # ---- [1/6] 版本号
    print_section(1, total, "版本号")
    old_code, old_name = read_gradle_version(str(GRADLE_FILE))
    print(f"  Gradle 当前：versionCode={old_code}  versionName={old_name}（tag v{old_name}）")

    bump_plan = None
    target_code, target_version = old_code, old_name
    if args.bump:
        bump_plan = plan_bump(str(GRADLE_FILE), args.bump)
        target_code, target_version = bump_plan["newCode"], bump_plan["newName"]
        print(
            f"  递增计划（--bump {args.bump}）：versionCode {old_code} → {target_code}，"
            f"versionName {old_name} → {target_version}（tag v{target_version}）"
        )
        if execute:
            write_gradle_text(str(GRADLE_FILE), bump_plan["newText"])
            state["gradle_written"] = True
            print(f"  ✔ 已写回 {rel_path(GRADLE_FILE)}")
        else:
            print(f"  dry-run：不写回 {rel_path(GRADLE_FILE)}；加 --execute 才会修改。")
    else:
        print("  未指定 --bump：版本号保持不变（需要递增时加 --bump patch|minor）。")

    # ---- [2/6] Release 构建与 APK 产物
    print_section(2, total, "Release 构建与 APK 产物")
    if args.skip_build:
        print("  已指定 --skip-build：跳过构建，直接使用现有 APK 产物。")
    elif execute:
        print(f"  构建任务：{DEFAULT_BUILD_TASK}")
        run_step(
            build_command(),
            "Release 构建",
            hint="请确认 JDK 17+（推荐 21）与 Android SDK 可用；Windows 走 scripts\\gw.cmd 统一入口。",
        )
    else:
        print(f"  dry-run：不会构建。将执行：{format_command(build_command())}")

    apks, ignored = collect_apks()
    print(f"  扫描目录：{rel_path(APK_DIR)}")
    print_apk_table(apks)
    if ignored:
        print(f"  忽略（不进入 update.json）：{', '.join(ignored)}")

    versions = read_output_metadata()
    if versions is not None:
        metadata_desc = "、".join(f"{c}/{n}" for c, n in sorted(versions))
        print(f"  output-metadata 记录版本：{metadata_desc}")
        if (target_code, target_version) not in versions:
            if execute:
                raise ReleaseError(
                    f"现有 APK 版本（{metadata_desc}）与目标 {target_code}/{target_version} 不一致；"
                    "请去掉 --skip-build 重新构建，或确认 --bump 已生效。"
                )
            print("  提示：dry-run 下现有产物版本与目标不同属正常（本次不构建，仅供预览）。")
    if execute:
        missing = [abi for abi in ABI_ORDER if abi not in apks]
        if missing:
            raise ReleaseError(
                f"缺少 APK：{', '.join(missing)}。请先构建（不要同时使用 --skip-build），"
                f"期望文件：{', '.join(f'app-{abi}-release.apk' for abi in missing)}"
            )
        ensure_apk_version(target_code, target_version, apks)

        print("\n  复制发布资产（重命名为 GitHub Release 资产名）：")
        staging_dir, _ = stage_assets(apks, target_version)
        state["staging_dir"] = staging_dir

    # ---- [3/6] docs/update.json
    print_section(3, total, rel_path(MANIFEST_FILE))
    existing_manifest = load_manifest()
    changelog = update_changelog(existing_manifest.get("changelog") or "", target_version, args.changelog)
    new_manifest = build_manifest(
        existing_manifest,
        target_version,
        target_code,
        changelog,
        apks,
        allow_missing=not execute,
    )
    print_manifest_summary(new_manifest)
    if execute:
        atomic_write_text(MANIFEST_FILE, manifest_text(new_manifest))
        state["manifest_written"] = True
        print(f"  ✔ 已写入 {rel_path(MANIFEST_FILE)}（待签名）")
    else:
        print(f"  dry-run：不写入 {rel_path(MANIFEST_FILE)}；--execute 时按上述内容写回。")

    # ---- [4/6] 清单签名
    print_section(4, total, "清单签名（Ed25519）")
    if not SIGNING_KEY.is_file():
        message = (
            f"未找到签名私钥 {rel_path(SIGNING_KEY)}；无法签名 update.json。"
            "请先运行 python scripts/gen_update_signing_key.py 生成（或从安全备份恢复）。"
        )
        if execute:
            raise ReleaseError(message)
        print(f"  警告：{message}")
    else:
        print(f"  私钥：{rel_path(SIGNING_KEY)}（内容不会打印）")
    if execute:
        run_step(
            sign_command(),
            "清单签名",
            hint="签名依赖 openssl（Windows 可用 Git 自带的 openssl）或 cryptography；脚本会给出具体原因。",
        )
    else:
        print(f"  将执行：{format_command(sign_command())}")

    # ---- [5/6] Gitee
    print_section(5, total, "Gitee 镜像同步")
    gitee_done = False
    token_source = gitee_token_source()
    if args.skip_gitee:
        print("  已指定 --skip-gitee：跳过 Gitee 同步。")
        print(f"  发布 GitHub Release 后可运行：{format_command(gitee_command(target_version))}")
    elif token_source is None:
        print("  未检测到 GITEE_TOKEN（环境变量或 ~/.lanlink/gitee_token），本次跳过 Gitee 同步。")
        print(f"  配置 token 后可运行：{format_command(gitee_command(target_version))}")
    elif not execute:
        print(f"  token 来源：{token_source}（命令参数中不含 token）")
        print(f"  将执行：{format_command(gitee_command(target_version))}")
    else:
        print(f"  token 来源：{token_source}（不会打印）")
        print("  注意：sync_gitee.py 从 GitHub Releases 拉取资产，需 GitHub Release 已发布；")
        print("        本步骤可能更新 docs/gitee/files.json。")
        run_step(
            gitee_command(target_version),
            "Gitee 同步",
            hint=(
                "常见原因：GitHub Release 尚未创建/资产未上传、token 权限不足或 Gitee 限流。"
                f"可先加 --skip-gitee 完成其余步骤，发布 Release 后再运行 {format_command(gitee_command(target_version))}"
            ),
        )
        gitee_done = True

    # ---- [6/6] 一致性自检
    print_section(6, total, "发布一致性自检")
    if execute:
        run_step(
            consistency_command(),
            "发布一致性自检",
            hint="该步骤校验 Gradle 版本 ↔ update.json ↔ 本地 APK 的 size/sha256 ↔ HTTPS ↔ 签名。",
        )
    else:
        print(f"  将执行：{format_command(consistency_command())}")

    # ---- 命令清单（dry-run 必须完整打印）
    if not execute:
        commands = []
        if not args.skip_build:
            commands.append(("Release 构建", build_command()))
        commands.append(("清单签名", sign_command()))
        if not args.skip_gitee:
            commands.append(("Gitee 同步", gitee_command(target_version)))
        commands.append(("一致性自检", consistency_command()))
        print_commands(commands)
        print("\n将修改/产生的文件：")
        if bump_plan is not None:
            print(f"  - {rel_path(GRADLE_FILE)}（versionCode/versionName）")
        print(f"  - {rel_path(MANIFEST_FILE)}（版本/URL/apks/changelog/sig）")
        print("  - app/build/outputs/apk/release/（构建产物，build 目录已 gitignore）")
        print(f"  - app/build/outputs/release-assets/v{target_version}/（3 个重命名 APK 副本）")
        if not args.skip_gitee and token_source is not None:
            print("  - docs/gitee/files.json（sync_gitee.py 生成/更新）")

    # ---- 人工步骤
    staging_dir = state.get("staging_dir")
    print_manual_steps(target_version, staging_dir, gitee_done)

    print("\n" + "=" * 78)
    if execute:
        print(f"EXECUTE 完成：v{target_version} 的构建、清单签名与自检已跑完。")
        print("GitHub Release 创建/资产上传、站点部署与公告仍需按上方人工步骤完成。")
        print("提示：本脚本不会 git commit / git push。")
    else:
        print("DRY-RUN 完成：未修改任何文件、未构建、未上传、未同步。")
        print("确认计划无误后，加 --execute 真正执行（建议首次加 --skip-gitee）。")
    print("=" * 78)
    return 0


def run(args, execute: bool) -> int:
    state = {"gradle_written": False, "manifest_written": False, "staging_dir": None}
    try:
        return _run(args, execute, state)
    except (ReleaseError, VersionError) as error:
        print(f"\n[发布已停止] {error}", file=sys.stderr)
        if state["gradle_written"]:
            print("  版本号已写回 app/build.gradle.kts；如需回滚：git checkout -- app/build.gradle.kts", file=sys.stderr)
        if state["manifest_written"]:
            print("  docs/update.json 已写入；如需回滚：git checkout -- docs/update.json", file=sys.stderr)
        if state["staging_dir"] is not None:
            print(f"  发布资产副本目录：{rel_path(state['staging_dir'])}（构建产物，可保留或删除）", file=sys.stderr)
        return 1


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "LanLink 一键发布脚本：默认 dry-run（只打印计划与命令，不改文件/不构建/不上传）；"
            "必须显式 --execute 才真正执行。"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python scripts/release.py                       # dry-run 预览（不递增版本）\n"
            "  python scripts/release.py --bump patch          # dry-run 预览 patch 递增\n"
            "  python scripts/release.py --bump minor --execute --skip-gitee\n"
            "  python scripts/release.py --execute --skip-build --skip-gitee\n"
        ),
    )
    parser.add_argument("--bump", choices=["patch", "minor"], default=None, help="递增版本号：patch 递增 Z，minor 递增 Y 并清零 Z（同 versionCode+1）")
    parser.add_argument("--dry-run", dest="dry_run", action="store_true", default=True, help="dry-run：只打印计划与命令（默认行为）")
    parser.add_argument("--execute", dest="dry_run", action="store_false", help="真正执行：写版本号/构建/更新清单/签名/同步（默认关闭）")
    parser.add_argument("--skip-build", action="store_true", help="跳过 :app:assembleRelease，直接使用现有 APK 产物")
    parser.add_argument("--skip-gitee", action="store_true", help="跳过 Gitee 镜像同步（GitHub Release 尚未发布时使用）")
    parser.add_argument("--changelog", default=None, help="可选：覆盖 update.json 的 changelog 正文；脚本自动补「LanLink vX.Y.Z：」首行")
    args = parser.parse_args(argv)
    return args


def _configure_output_encoding() -> None:
    """Windows 下重定向输出时默认用本地编码（GBK），统一改为 UTF-8，避免中文乱码。"""
    if os.name != "nt":
        return
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError, ValueError):
            pass


def main(argv=None) -> int:
    _configure_output_encoding()
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--dry-run" in argv and "--execute" in argv:
        print("[错误] --dry-run 与 --execute 不能同时使用（默认就是 dry-run）。", file=sys.stderr)
        return 2
    args = parse_args(argv)
    try:
        return run(args, execute=not args.dry_run)
    except KeyboardInterrupt:
        print("\n[已中断] 用户取消；若已在 --execute 中途，请用 git status 检查已改动的文件。", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
