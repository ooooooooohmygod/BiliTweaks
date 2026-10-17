#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""bili-noads · toy 全链路自测

用自带 toy 类走完整链路验证 patcher 正确性：
  smali 回编 -> baksmali 反汇编 -> patcher 打补丁 -> 断言 -> smali 回编 -> 回读断言
外加两条负路径（方法不存在 / 改动数不符）。

用法:
  python3 test/selftest.py
环境变量:
  SMALI / BAKSMALI  形如 "java -jar tools/smali.jar"；缺省用 PATH 里的同名命令
"""
from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'src'))

from patcher import PatchError, apply_rules, load_rules  # noqa: E402


def resolve(name):
    spec = os.environ.get(name.upper())
    if spec:
        return shlex.split(spec)
    found = shutil.which(name)
    if found:
        return [found]
    jar = ROOT / 'tools' / f'{name}.jar'
    if jar.is_file():
        return ['java', '-jar', str(jar)]
    raise SystemExit(f'缺少 {name}（安装命令版，或设置环境变量 {name.upper()}="java -jar <jar>"）')


def run(cmd):
    print('  $', ' '.join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True)


def check(cond, msg):
    if not cond:
        print(f'[selftest] 失败: {msg}', file=sys.stderr)
        sys.exit(1)


def expect_patch_error(fn, label):
    try:
        fn()
    except PatchError:
        return
    print(f'[selftest] 失败: {label} 未被拦截', file=sys.stderr)
    sys.exit(1)


def main():
    smali = resolve('smali')
    baksmali = resolve('baksmali')
    tmp = Path(tempfile.mkdtemp(prefix='bili-noads-selftest-'))
    print(f'[selftest] 工作目录: {tmp}')

    # 1. 编译 toy dex
    run([*smali, 'a', ROOT / 'test' / 'toy', '-o', tmp / 'toy.dex'])
    check((tmp / 'toy.dex').is_file(), 'toy.dex 未生成')

    # 2. 反汇编
    smali_root = tmp / 'smali'
    run([*baksmali, 'd', tmp / 'toy.dex', '-o', smali_root / 'toy.dex'])
    flag_file = smali_root / 'toy.dex' / 'test' / 'ToyFlag.smali'
    splash_file = smali_root / 'toy.dex' / 'test' / 'ToySplash.smali'
    check(flag_file.is_file() and splash_file.is_file(), '反汇编输出缺少 toy 类')

    flag_orig = flag_file.read_text(encoding='utf-8')
    splash_orig = splash_file.read_text(encoding='utf-8')

    # 3. 打补丁
    rules = load_rules(ROOT / 'test' / 'toy-rules.json')
    report = {r['id']: r['changes'] for r in apply_rules(rules, smali_root)}
    check(report.get('toy-flag') == 1, f'toy-flag 改动数异常: {report}')
    check(report.get('toy-splash') == 2, f'toy-splash 改动数异常: {report}')

    # 4. 文本断言
    flag = flag_file.read_text(encoding='utf-8')
    splash = splash_file.read_text(encoding='utf-8')
    check('const/4 v0, 0x0' in flag and 'return v0' in flag, 'force_false 未生效')
    check('const/4 v0, 0x1' not in flag, 'force_false 残留原返回值')
    check('sget-object' in splash, 'null_returns 误删原有指令（副作用丢失）')
    n = len(re.findall(r'const/4 v0, 0x0\s*\n\s*return-object v0', splash))
    check(n == 2, f'null_returns 插入数量异常: {n}')

    # 5. 回编 + 回读断言
    run([*smali, 'a', smali_root / 'toy.dex', '-o', tmp / 'toy-patched.dex'])
    back_root = tmp / 'back'
    run([*baksmali, 'd', tmp / 'toy-patched.dex', '-o', back_root])
    flag_back = (back_root / 'test' / 'ToyFlag.smali').read_text(encoding='utf-8')
    splash_back = (back_root / 'test' / 'ToySplash.smali').read_text(encoding='utf-8')
    check('return v0' in flag_back and 'const/4 v0, 0x1' not in flag_back,
          '回读: force_false 不符预期')
    n = len(re.findall(r'const/4 v0, 0x0\s*\n\s*return-object v0', splash_back))
    check(n == 2, f'回读: null_returns 数量异常: {n}')

    # 6. 负路径（先还原到未打补丁状态再做校验类测试）
    flag_file.write_text(flag_orig, encoding='utf-8')
    splash_file.write_text(splash_orig, encoding='utf-8')

    bad_sig = {'patches': [{'id': 'bad', 'dex': 'toy.dex', 'class': 'Ltest/ToyFlag;',
                            'method': 'enabled(I)Z', 'op': 'force_false'}]}
    expect_patch_error(lambda: apply_rules(bad_sig, smali_root), '错误签名')

    bad_expect = {'patches': [{'id': 'bad2', 'dex': 'toy.dex', 'class': 'Ltest/ToySplash;',
                               'method': 'current()Ljava/lang/String;', 'op': 'null_returns',
                               'expect_changes': 3}]}
    expect_patch_error(lambda: apply_rules(bad_expect, smali_root), '改动数不符')

    print('[selftest] OK：全链路通过（含 2 条负路径）')


if __name__ == '__main__':
    main()
