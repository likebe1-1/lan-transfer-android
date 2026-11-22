#!/usr/bin/env python3
"""LanLink 版本号工具：读取与递增 app/build.gradle.kts 的 versionCode / versionName。

设计为 scripts/release.py 的复用模块，也可单独当命令行使用（默认 dry-run）。

用法：
    python scripts/bump_version.py --show                  # 只打印当前版本
    python scripts/bump_version.py --bump patch            # 只打印递增计划（不写文件）
    python scripts/bump_version.py --bump minor --execute  # 真正写回 build.gradle.kts

规则：
    - versionCode 单调 +1；
    - versionName 采用 X.Y.Z：patch 递增 Z，minor 递增 Y 并清零 Z；
    - 只改 defaultConfig 里的这两行，保留缩进与换行风格。

退出码：
    0 成功；1 版本号无法解析或写回失败。
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

DEFAULT_GRADLE = "app/build.gradle.kts"

# 只匹配独占一行的 versionCode / versionName 赋值，避免误改注释或其它字段
CODE_RE = re.compile(r"(?m)^([ \t]*)versionCode([ \t]*=[ \t]*)(\d+)([ \t]*\r?)$")
NAME_RE = re.compile(r'(?m)^([ \t]*)versionName([ \t]*=[ \t]*")([^"\r\n]*)(")([ \t]*\r?)$')
SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


class VersionError(Exception):
    """版本号解析/写回失败；消息面向用户，中文可读。"""


def read_version_text(text: str):
    """从 build.gradle.kts 文本中解析 (versionCode, versionName)。"""
    code_match = CODE_RE.search(text)
    name_match = NAME_RE.search(text)
    if not code_match or not name_match:
        raise VersionError(
            "无法在 app/build.gradle.kts 中解析 versionCode / versionName"
            "（期望形如 versionCode = 11、versionName = \"2.3.0\" 各自独占一行）"
        )
    return int(code_match.group(3)), name_match.group(3)


def read_gradle_version(path: str = DEFAULT_GRADLE):
    """读取 build.gradle.kts 的 (versionCode, versionName)。"""
    try:
        with open(path, encoding="utf-8", newline="") as handle:
            text = handle.read()
    except FileNotFoundError as error:
        raise VersionError(f"找不到 {path}，请在仓库根目录运行（或显式传 --file）") from error
    except OSError as error:
        raise VersionError(f"读取 {path} 失败：{error}") from error
    return read_version_text(text)


def bump_version_name(name: str, bump: str) -> str:
    """按 patch/minor 规则递增 X.Y.Z 形式的 versionName。"""
    match = SEMVER_RE.match(name.strip())
    if not match:
        raise VersionError(f"versionName「{name}」不是 X.Y.Z 形式，无法自动递增；请先手动改成 X.Y.Z")
    major, minor, patch = (int(part) for part in match.groups())
    if bump == "patch":
        patch += 1
    elif bump == "minor":
        minor += 1
        patch = 0
    else:
        raise VersionError(f"不支持的递增类型：{bump}（仅支持 patch / minor）")
    return f"{major}.{minor}.{patch}"


def replace_version_text(text: str, new_code: int, new_name: str) -> str:
    """把文本中的 versionCode / versionName 替换为目标值；各必须恰好出现一次。"""

    def code_repl(match: re.Match) -> str:
        return f"{match.group(1)}versionCode{match.group(2)}{new_code}{match.group(4)}"

    def name_repl(match: re.Match) -> str:
        return (
            f"{match.group(1)}versionName{match.group(2)}"
            f"{new_name}{match.group(4)}{match.group(5)}"
        )

    text, code_count = CODE_RE.subn(code_repl, text)
    if code_count != 1:
        raise VersionError(f"app/build.gradle.kts 中 versionCode 匹配到 {code_count} 次（期望 1 次），为避免误改已停止")
    text, name_count = NAME_RE.subn(name_repl, text)
    if name_count != 1:
        raise VersionError(f"app/build.gradle.kts 中 versionName 匹配到 {name_count} 次（期望 1 次），为避免误改已停止")
    return text


def plan_bump(path: str = DEFAULT_GRADLE, bump: str = "patch"):
    """读取文件并计算递增计划；不写文件。返回包含新旧版本与新旧文本的 dict。"""
    try:
        with open(path, encoding="utf-8", newline="") as handle:
            text = handle.read()
    except FileNotFoundError as error:
        raise VersionError(f"找不到 {path}，请在仓库根目录运行") from error
    except OSError as error:
        raise VersionError(f"读取 {path} 失败：{error}") from error

    old_code, old_name = read_version_text(text)
    new_name = bump_version_name(old_name, bump)
    new_code = old_code + 1
    new_text = replace_version_text(text, new_code, new_name)
    return {
        "path": path,
        "oldCode": old_code,
        "oldName": old_name,
        "newCode": new_code,
        "newName": new_name,
        "oldText": text,
        "newText": new_text,
    }


def write_gradle_text(path: str, text: str) -> None:
    """原子写回 build.gradle.kts（先写 .tmp 再 replace），保留原换行风格。"""
    target = Path(path)
    temp = target.with_name(target.name + ".tmp")
    try:
        with open(temp, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        os.replace(temp, target)
    except OSError as error:
        try:
            temp.unlink()
        except OSError:
            pass
        raise VersionError(f"写回 {path} 失败：{error}") from error


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
    parser = argparse.ArgumentParser(
        description="LanLink 版本号工具：读取/递增 app/build.gradle.kts 的 versionCode 与 versionName（默认 dry-run）",
    )
    parser.add_argument("--bump", choices=["patch", "minor"], default=None, help="递增类型：patch 递增 Z，minor 递增 Y 并清零 Z")
    parser.add_argument("--execute", action="store_true", help="真正写回 build.gradle.kts（默认只打印计划）")
    parser.add_argument("--show", action="store_true", help="只打印当前版本号")
    parser.add_argument("--file", default=DEFAULT_GRADLE, help=f"build.gradle.kts 路径（默认 {DEFAULT_GRADLE}）")
    args = parser.parse_args(argv)

    try:
        if args.show or args.bump is None:
            code, name = read_gradle_version(args.file)
            print(f"当前版本：versionCode={code} versionName={name}")
            if args.bump is None and not args.show:
                print("提示：加 --bump patch|minor 查看递增计划")
            return 0

        plan = plan_bump(args.file, args.bump)
        print(
            f"版本计划（--bump {args.bump}）：versionCode {plan['oldCode']} → {plan['newCode']}，"
            f"versionName {plan['oldName']} → {plan['newName']}"
        )
        if args.execute:
            write_gradle_text(args.file, plan["newText"])
            print(f"已写回 {args.file}")
        else:
            print("dry-run：未修改任何文件；加 --execute 才会写回")
        return 0
    except VersionError as error:
        print(f"[错误] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
