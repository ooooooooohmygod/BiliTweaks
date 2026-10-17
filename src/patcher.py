#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""bili-noads · 补丁应用器

把 patches/rules.json 里的补丁规则应用到 baksmali 反汇编目录。

支持的操作（见规则文件 ops 字段）:
  force_false   把方法体替换为 const/4 v0, 0x0 + return v0（布尔方法恒 false）
  null_returns  在该方法的每个 return-object vX 前插入 const/4 vX, 0x0
                （引用返回值恒 null，保留原有副作用）

用法:
  python3 src/patcher.py --rules patches/rules.json --smali-root <目录> [--dry-run]

smali-root 约定结构: <smali-root>/<dex名>/<包路径>/<类>.smali（每个 dex 一个子目录）
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

METHOD_RE = re.compile(r'^\.method[^\n]*\n.*?^\.end method[^\n]*$', re.M | re.S)


class PatchError(Exception):
    def __init__(self, msg):
        super().__init__(msg)


def load_rules(path):
    with open(path, encoding='utf-8') as f:
        rules = json.load(f)
    if not isinstance(rules.get('patches'), list) or not rules['patches']:
        raise PatchError('规则文件缺少非空的 patches 列表')
    return rules


def locate_class_file(smali_root, dex, class_desc):
    if not (class_desc.startswith('L') and class_desc.endswith(';')):
        raise PatchError(f'类描述符不合法: {class_desc}')
    rel = class_desc[1:-1] + '.smali'
    direct = Path(smali_root) / dex / rel
    if direct.is_file():
        return direct
    hits = sorted(Path(smali_root).rglob(Path(rel).name))
    if len(hits) == 1:
        return hits[0]
    raise PatchError(
        f'找不到类文件: {dex} {class_desc}（直接路径 {direct} 不存在，全树候选 {len(hits)} 个）'
    )


def find_method_block(text, method_sig):
    """按 `方法名(参数)返回类型` 精确定位方法块。

    返回 (start, end, block) 或 None；block 含 .method 到 .end method 的完整文本。
    """
    for m in METHOD_RE.finditer(text):
        block = m.group(0)
        head = block.split('\n', 1)[0]
        tokens = head.split()
        if len(tokens) >= 2 and tokens[-1] == method_sig:
            return m.start(), m.end(), block
    return None


def op_null_returns(block):
    """在每个 return-object vX 前插入 const/4 vX, 0x0。返回 (新块, 改动数)。"""
    out = []
    changes = 0
    for line in block.split('\n'):
        m = re.match(r'^(\s*)return-object (v\d+)\s*$', line)
        if m:
            indent, reg = m.group(1), m.group(2)
            out.append(f'{indent}const/4 {reg}, 0x0')
            changes += 1
        out.append(line)
    return '\n'.join(out), changes


def op_force_false(block):
    """把方法体替换为 const/4 v0, 0x0 + return v0（保留 .registers/.locals）。"""
    lines = block.split('\n')
    head, tail = lines[0], lines[-1]
    if not head.split()[-1].rstrip().endswith('Z'):
        return block, 0  # 只适用于布尔方法
    keep = [ln for ln in lines[1:-1] if ln.strip().startswith(('.registers', '.locals'))]
    new_lines = [head] + keep + ['', '    const/4 v0, 0x0', '', '    return v0', tail]
    return '\n'.join(new_lines), 1


OPS = {
    'force_false': op_force_false,
    'null_returns': op_null_returns,
}


def apply_rules(rules, smali_root, dry_run=False):
    report = []
    for p in rules['patches']:
        for key in ('id', 'dex', 'class', 'method', 'op'):
            if key not in p:
                raise PatchError(f'规则缺少字段 {key}: {p!r}')
        op = OPS.get(p['op'])
        if op is None:
            raise PatchError(f"未知操作 {p['op']}（规则 {p['id']}）")

        path = locate_class_file(smali_root, p['dex'], p['class'])
        text = path.read_text(encoding='utf-8')
        found = find_method_block(text, p['method'])
        if found is None:
            raise PatchError(
                f"未找到方法 {p['class']} {p['method']}（规则 {p['id']}，文件 {path}）"
            )
        start, end, block = found
        new_block, changes = op(block)

        expect = p.get('expect_changes')
        if expect is not None and changes != expect:
            raise PatchError(
                f"改动数不符（规则 {p['id']}）：预期 {expect}，实际 {changes}。"
                '目标版本可能已变化，请勿盲目继续'
            )
        if new_block == block:
            raise PatchError(f"无变化（规则 {p['id']}）")

        if not dry_run:
            path.write_text(text[:start] + new_block + text[end:], encoding='utf-8')
        report.append({'id': p['id'], 'file': str(path), 'changes': changes})
    return report


def main(argv=None):
    ap = argparse.ArgumentParser(description='bili-noads 补丁应用器')
    ap.add_argument('--rules', required=True, help='规则文件路径（如 patches/rules.json）')
    ap.add_argument('--smali-root', required=True, help='baksmali 反汇编根目录')
    ap.add_argument('--dry-run', action='store_true', help='只检查不写回')
    args = ap.parse_args(argv)

    try:
        rules = load_rules(args.rules)
        report = apply_rules(rules, args.smali_root, dry_run=args.dry_run)
    except PatchError as e:
        print(f'[patcher] 失败: {e}', file=sys.stderr)
        return 1

    verb = '检查' if args.dry_run else '已应用'
    for r in report:
        print(f"[patcher] {verb} {r['id']}: {r['changes']} 处 -> {r['file']}")
    print(f"[patcher] 共 {len(report)} 条规则，{'演练完成' if args.dry_run else '全部成功'}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
