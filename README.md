# bili-noads

哔哩哔哩 Android 客户端 **9.15.0（arm64-v8a）** 去广告补丁工程。

补丁以「规则 + 脚本」的形式公开：**本仓库不包含、不发布任何成品 APK**。
请自备官方客户端，用这里的工具自行构建，供个人使用。

## 构建后效果

| 场景 | 官方原版 | 打补丁后 |
|---|---|---|
| 冷启动 / 热启动 / 唤起 | 展示开屏广告页 | 直接进入主界面 |
| 直播热启动开屏 | 展示开屏广告 | 不展示 |
| 暂停 / 双击暂停 | 插入暂停页广告 | 不插入 |
| 视频结束页 | 可能展示结束页广告 | 不展示 |

其余功能（播放、弹幕、搜索、动态、投稿等）不做任何修改。

## 补丁清单（6 个方法 / 4 个 dex）

| id | 目标方法 | 操作 | 效果 |
|---|---|---|---|
| `paused-page-enable` | `DDConfig.getPausePageEnable()` | 恒 false | 暂停页流程总开关关闭 |
| `paused-page-decode` | `PausedPageService.R(byte[], boolean)` | 恒 false | 结束页广告不再解码调度 |
| `paused-page-fallback` | `PausedPageService.e0(byte[])` | 恒 false | 结束页兜底展示关闭 |
| `splash-tv-orders` | `MainActivitySplashComponentExtKt.a(MainActivityV2)` | 返回 null | TV 侧开屏订单全部为空 |
| `splash-kntr-content` | `BusinessSplashViewModel.a(SplashSource)` | 返回 null | kntr 侧开屏内容为空，容器自动隐藏 |
| `splash-pages` | `SplashManager.a(SplashOrder)` | 返回 null | 开屏页面工厂为空，入口即退 |

原理、取舍与自查方法见 [docs/PATCHES.md](docs/PATCHES.md)。

## 快速开始

### 1. 依赖

- Python 3.8+
- Java 17+（baksmali / smali 需要）
- `zipalign`、`apksigner`（Android SDK build-tools，或系统包；Debian/Ubuntu 可直接 `apt install apksigner zipalign`）
- `smali`、`baksmali`：
  - Debian / Ubuntu：`sudo apt install libsmali-java`
  - 其他系统：把官方 fat jar 放入 `tools/`（下载命令见 `tools/README.txt`），构建脚本会自动使用

### 2. 构建

```bash
bash scripts/build.sh --input /path/to/哔哩哔哩_v9.15.0_arm64.apk \
                      --out   bili-noads-9.15.0.apk
```

完成后会打印输出路径、大小、MD5，并自动执行 `apksigner verify` 与 `zipalign -c 4` 校验。

流程：提取 dex → baksmali 反汇编 → 应用补丁规则 → smali 回编 → 逐条重打包 → zipalign → 签名。
任一步骤校验失败（例如方法签名对不上）会**直接报错退出**，不会静默产出坏包。

### 3. 安装

- 构建使用自签名密钥（默认 `~/.bili-noads/keystore.jks`，首次自动生成并复用）
- 自签名与官方签名不同：设备上如装有官方客户端，需先卸载（或与旧构建同密钥覆盖安装）
- 本工具默认复用同一密钥库，因此**本工具构建的各个版本可互相覆盖安装**

## 目录结构

```
patches/rules.json     补丁规则（类 / 方法 / 操作 / 预期改动数）
src/patcher.py         补丁应用器（规则 -> smali 树）
src/build.py           构建编排（官方 APK -> 去广告 APK）
scripts/build.sh       一键构建入口
test/                  toy 样本与全链路自测（CI 执行）
docs/PATCHES.md        补丁原理、边界与自查方法
```

## 适配其他版本

`patches/rules.json` 锁定 9.15.0 的类与方法签名。升级客户端版本后：

1. 用 jadx / baksmali 按表里的类名与方法特征定位新版本对应方法
2. 更新规则里的 `class` / `method` / `expect_changes`
3. 跑 `python3 test/check_rules.py`，再构建验证

patcher 对「找不到方法」「改动数不符」会直接报错，避免版本漂移后漏改。

## CI

- **CI**：每次 push / PR 执行 toy 全链路自测（真实 smali 编译 → baksmali 反汇编 → 打补丁 → 回编 → 回读断言，含负路径）与规则结构校验
- **Release**：推送 `v*` tag 时打包脚本与工具为 `bili-noads-toolkit-*.zip` 发布——依旧不含任何成品 APK

## 免责声明

- 本工程仅供个人学习、研究使用；请勿分发、售卖构建产物
- 构建产物与官方客户端的兼容性、稳定性风险自负
- 请支持哔哩哔哩官方客户端；遇到问题请使用官方版本反馈
- 本工程不包含哔哩哔哩的任何代码、资源或反编译内容；如相关方有异议，请联系删除

## 许可

MIT，仅覆盖本工程自身的脚本与文档。
