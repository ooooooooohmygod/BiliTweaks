#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""bili-noads · 规则文件结构校验（CI 用）"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
rules = json.loads((ROOT / 'patches' / 'rules.json').read_text(encoding='utf-8'))

patches = rules.get('patches')
assert isinstance(patches, list) and patches, 'patches 必须是非空列表'
ids = [p['id'] for p in patches]
assert len(set(ids)) == len(ids), '规则 id 重复'
for p in patches:
    for key in ('id', 'dex', 'class', 'method', 'op', 'expect_changes'):
        assert key in p, f"{p.get('id')} 缺少字段 {key}"
    assert p['op'] in rules['ops'], f"{p['id']} 未知 op: {p['op']}"
    assert p['class'].startswith('L') and p['class'].endswith(';'), f"{p['id']} 类描述符不合法"
    assert p['dex'].endswith('.dex'), f"{p['id']} dex 名不合法"
    assert isinstance(p['expect_changes'], int) and p['expect_changes'] > 0

print(f'[check_rules] {len(patches)} 条规则结构校验通过')
for p in patches:
    print(f"  - {p['id']}  {p['dex']}  {p['class']} {p['method']}  [{p['op']}]")
sys.exit(0)
