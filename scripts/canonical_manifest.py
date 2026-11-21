#!/usr/bin/env python3
"""update.json 规范化载荷（签名与验签共用同一规则）。

规则：
- 深拷贝 JSON，递归删除所有 "sig" 键；
- 键按字典序排序；
- 紧凑分隔符（',' 与 ':' 无空格），UTF-8，不转义非 ASCII。

App 端 UpdateManifestVerifier.canonicalPayload() 必须实现完全相同的规则。
"""
import json


def strip_sig(value):
    if isinstance(value, dict):
        return {key: strip_sig(item) for key, item in value.items() if key != "sig"}
    if isinstance(value, list):
        return [strip_sig(item) for item in value]
    return value


def canonical(data) -> str:
    return json.dumps(strip_sig(data), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
