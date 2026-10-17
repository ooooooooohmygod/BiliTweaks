#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""bili-noads · 构建编排

给定官方 APK，自动完成：提取 dex -> 反汇编 -> 打补丁 -> 回编 -> 重打包 -> 对齐 -> 签名 -> 校验。

用法:
  python3 src/build.py --input 官方.apk --out 去广告.apk [选项]

依赖:
  java、baksmali、smali（命令版或 "java -jar *.jar"）、zipalign、apksigner、keytool
  可用 --baksmali/--smali/--zipalign/--apksigner 或环境变量 BAKSMALI/SMALI 指定。

说明:
  * 签名默认使用 ~/.bili-noads/keystore.jks（首次自动生成，后续复用以支持覆盖安装）
  * 未签名包会保留在工作目录内，方便排查
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'src'))

from patcher import PatchError, apply_rules, load_rules  # noqa: E402

DEX_API_BY_VERSION = {'035': '23', '037': '24', '038': '26', '039': '28', '040': '30'}


def run(cmd, **kw):
    print('  $ ' + ' '.join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def resolve(name, cli_value, env_name):
    spec = cli_value or os.environ.get(env_name)
    if spec:
        return shlex.split(spec)
    found = shutil.which(name)
    if found:
        return [found]
    jar = ROOT / 'tools' / f'{name}.jar'
    if jar.is_file():
        return ['java', '-jar', str(jar)]
    raise SystemExit(
        f'找不到 {name}：请安装同名命令，或设置环境变量 {env_name}="java -jar <路径>.jar"'
    )


def extract_dex(apk, dex_names, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        missing = [d for d in dex_names if d not in names]
        if missing:
            raise SystemExit(f'输入 APK 缺少以下 dex：{missing}（版本不匹配？）')
        paths = []
        for d in dex_names:
            p = out_dir / d
            p.write_bytes(z.read(d))
            paths.append(p)
    return paths


def dex_api_level(dex_bytes):
    magic = dex_bytes[:8].decode('ascii', 'replace')
    ver = magic[4:7]
    return DEX_API_BY_VERSION.get(ver, '24')


def repack(base_apk, replace_map, out_apk):
    """逐条重建 zip，替换指定条目，其余保持原压缩方式与属性。"""
    with zipfile.ZipFile(base_apk) as zin, \
         zipfile.ZipFile(out_apk, 'w', allowZip64=True) as zw:
        infos = zin.infolist()
        total = len(infos)
        for n, i in enumerate(infos, 1):
            zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
            zi.compress_type = i.compress_type
            zi.external_attr = i.external_attr
            zi.internal_attr = i.internal_attr
            zi.create_system = i.create_system
            with zw.open(zi, 'w') as wf:
                if i.filename in replace_map:
                    with open(replace_map[i.filename], 'rb') as f:
                        shutil.copyfileobj(f, wf, 1024 * 1024)
                else:
                    with zin.open(i) as f:
                        shutil.copyfileobj(f, wf, 1024 * 1024)
            if n % 4000 == 0 or n == total:
                print(f'  重打包 {n}/{total}', flush=True)


def ensure_keystore(keytool, ks_path, alias, store_pass, key_pass):
    if ks_path.is_file():
        print(f'  使用已有密钥库: {ks_path}')
        return
    ks_path.parent.mkdir(parents=True, exist_ok=True)
    print(f'  未找到密钥库，生成自签名密钥: {ks_path}')
    run([
        *keytool, '-genkeypair',
        '-keystore', ks_path,
        '-alias', alias,
        '-storepass', store_pass,
        '-keypass', key_pass,
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-dname', 'CN=bili-noads, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN',
    ])


def md5sum(path):
    h = hashlib.md5()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser(description='bili-noads 构建编排（官方 APK -> 去广告 APK）')
    ap.add_argument('--input', required=True, help='官方原版 APK 路径')
    ap.add_argument('--out', required=True, help='输出 APK 路径')
    ap.add_argument('--rules', default=str(ROOT / 'patches' / 'rules.json'))
    ap.add_argument('--workdir', default=None, help='工作目录（默认：输出文件同级的 .work 目录）')
    ap.add_argument('--keystore', default=None, help='自定义密钥库（默认 ~/.bili-noads/keystore.jks）')
    ap.add_argument('--alias', default='bilinoads')
    ap.add_argument('--ks-pass', default='bilinoads')
    ap.add_argument('--key-pass', default=None)
    ap.add_argument('--baksmali', default=None)
    ap.add_argument('--smali', default=None)
    ap.add_argument('--zipalign', default=None)
    ap.add_argument('--apksigner', default=None)
    ap.add_argument('--keytool', default=None)
    ap.add_argument('--keep-workdir', action='store_true')
    args = ap.parse_args(argv)

    key_pass = args.key_pass or args.ks_pass
    input_apk = Path(args.input).expanduser().resolve()
    out_apk = Path(args.out).expanduser().resolve()
    if not input_apk.is_file():
        raise SystemExit(f'输入不存在: {input_apk}')
    if input_apk == out_apk:
        raise SystemExit('输入与输出不能是同一个文件')
    out_apk.parent.mkdir(parents=True, exist_ok=True)
    workdir = Path(args.workdir).resolve() if args.workdir else out_apk.with_suffix('.work')
    workdir.mkdir(parents=True, exist_ok=True)

    baksmali = resolve('baksmali', args.baksmali, 'BAKSMALI')
    smali = resolve('smali', args.smali, 'SMALI')
    zipalign = resolve('zipalign', args.zipalign, 'ZIPALIGN')
    apksigner = resolve('apksigner', args.apksigner, 'APKSIGNER')
    keytool = resolve('keytool', args.keytool, 'KEYTOOL')

    rules = load_rules(args.rules)
    dex_names = sorted({p['dex'] for p in rules['patches']})
    print(f"bili-noads 构建开始")
    print(f"  目标版本: {rules['target']['versionName']} ({rules['target']['package']})")
    print(f"  涉及 dex: {dex_names}")
    print(f"  工作目录: {workdir}")

    ok = False
    try:
        # 1. 提取 dex
        print('[1/7] 提取 dex')
        dex_paths = extract_dex(input_apk, dex_names, workdir / 'dex')
        api = dex_api_level((workdir / 'dex' / dex_names[0]).read_bytes())
        print(f'  dex 格式 API 级别: {api}')

        # 2. 反汇编
        print('[2/7] baksmali 反汇编')
        smali_root = workdir / 'smali'
        for d, p in zip(dex_names, dex_paths):
            run([*baksmali, 'd', p, '-o', smali_root / d])

        # 3. 打补丁
        print('[3/7] 应用补丁规则')
        report = apply_rules(rules, smali_root)
        for r in report:
            print(f"  已应用 {r['id']}: {r['changes']} 处")

        # 4. 回编
        print('[4/7] smali 回编')
        patched = workdir / 'patched'
        patched.mkdir(exist_ok=True)
        replace_map = {}
        for d in dex_names:
            outdex = patched / d
            run([*smali, 'a', '-a', api, smali_root / d, '-o', outdex])
            replace_map[d] = outdex
            print(f"  {d}: {outdex.stat().st_size} 字节")

        # 5. 重打包
        print('[5/7] 重打包')
        unsigned = workdir / 'unsigned.apk'
        repack(input_apk, replace_map, unsigned)

        # 6. 对齐
        print('[6/7] zipalign 对齐')
        aligned = workdir / 'aligned.apk'
        run([*zipalign, '-f', '4', unsigned, aligned])

        # 7. 签名 + 校验
        print('[7/7] 签名与校验')
        ks_path = Path(args.keystore).expanduser() if args.keystore else Path.home() / '.bili-noads' / 'keystore.jks'
        ensure_keystore(keytool, ks_path, args.alias, args.ks_pass, key_pass)
        run([
            *apksigner, 'sign',
            '--ks', ks_path,
            '--ks-pass', f'pass:{args.ks_pass}',
            '--key-pass', f'pass:{key_pass}',
            '--ks-key-alias', args.alias,
            '--v1-signing-enabled', 'false',
            '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true',
            '--out', out_apk,
            aligned,
        ])
        run([*apksigner, 'verify', '--print-certs', out_apk])
        run([*zipalign, '-c', '4', out_apk])

        size = out_apk.stat().st_size
        print()
        print('构建完成 ✔')
        print(f'  输出: {out_apk}')
        print(f'  大小: {size:,} 字节')
        print(f'  MD5:  {md5sum(out_apk)}')
        ok = True
    except PatchError as e:
        print(f'\n[错误] 补丁失败: {e}', file=sys.stderr)
        print('工作目录已保留，可检查后重试。', file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as e:
        print(f'\n[错误] 外部工具执行失败（退出码 {e.returncode}）：{e.cmd}', file=sys.stderr)
        print('工作目录已保留，可检查后重试。', file=sys.stderr)
        return 1
    finally:
        if ok and not args.keep_workdir:
            shutil.rmtree(workdir, ignore_errors=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
