# 补丁原理与取舍

## 总体策略

所有补丁遵循同一原则：**只清返回值，不动流程**。

- 保留全部原有逻辑与副作用（网络请求、埋点、状态机照常走）
- 只让关键判断返回「没有广告可展示」，让 App 自己走正常分支
- 相比「删除调用」或「清空方法体」，能最大程度避免空指针、状态卡死等副作用

> 例外：去更新检测一组（第 7-10 节）没有「是否展示广告」这类安全返回值可用，采用整体禁用
> （`force_void` / `force_null`），直接跳过检查与弹窗链路；代价是「检查更新」入口点击后无反应。

## 支持的四种操作

| op | 行为 | 适用场景 |
|---|---|---|
| `force_false` | 方法体替换为 `const/4 v0, 0x0` + `return v0` | 布尔开关类方法 |
| `force_void` | 方法体替换为 `return-void` | 无返回值、纯执行动作的方法 |
| `force_null` | 方法体替换为 `const/4 v0, 0x0` + `return-object v0` | 返回对象、且需连同副作用跳过的方法 |
| `null_returns` | 每个 `return-object vX` 前插入 `const/4 vX, 0x0` | 返回对象的工厂 / 内容方法 |

## 各补丁说明

### 1. paused-page-enable — 暂停页总开关

`DDConfig.getPausePageEnable()` 是暂停页流程的总开关，在 `PausedPageService.t()`（处理暂停 / 双击事件）开头被检查：为 false 时直接 `skipPausedPageFlow`，不会走入任何暂停页广告逻辑。
恒 false 是「最小改动、最大覆盖」：暂停页广告整条流水线不再启动。

### 2. paused-page-decode / 3. paused-page-fallback — 结束页广告

视频结束页广告数据随播放结束流程下发：

- `PausedPageService.R(byte[], boolean)`：解码结束页广告数据并调度展示
- `PausedPageService.e0(byte[])`：结束页完成信号 / 兜底展示

两者只在「结束页类型为广告」时被调用。恒 false 后结束页不会再出现广告页，正常结束页行为不受影响。

### 4. splash-tv-orders — TV 侧开屏订单

`MainActivitySplashComponentExtKt.a(MainActivityV2)` 负责取开屏订单（冷启动 / 唤起 / 实时三条路径，对应两个返回分支）。
返回 null 后，主界面会话组件视为「无开屏」，直接正常启动。

### 5. splash-kntr-content — kntr 侧开屏内容

`BusinessSplashViewModel.a(SplashSource)` 返回当前开屏内容。
返回 null 后：空订单 → 触发 Dismiss → 开屏容器隐藏。冷启动 / 热启动两条路径都被覆盖。

### 6. splash-pages — 开屏页面工厂

`SplashManager.a(SplashOrder)` 是**所有** `BaseSplash` 子类页面（含 `HotSplashActivity`）的唯一创建入口。
返回 null 后：即使还有其它路径取出订单，页面也无法被实例化，入口 Activity 会立即退出。

### 7. update-check-startup — 启动检查总入口

`UpdateHelper.checkUpdateInStartup(Activity, IUpdater)` 是冷启动 / 回前台场景下静默检查（含「本地缓存强制更新」链路）的唯一入口。
整体禁用后：启动流程不再请求版本接口、不再读取本地更新包记录，也不会有任何强制更新弹窗。

### 8. update-check-manual — 手动检查 / 兼容性升级总入口

`UpdateHelper.checkUpdateAndShowDialog(Context, IUpdater)` 覆盖设置页「检查更新」与兼容性升级两个场景。
整体禁用后不再请求版本接口。副作用：设置里的「检查更新」入口点击后没有反应。

### 9. update-cached-force — 缓存的强制更新读取

`qs1.g.call()` 在后台读取本地已下载更新包记录（`UpdateApk.getExistingForceUpdate`），是「已缓存强制更新」弹窗的唯一数据来源。
恒 null 后，调用方（`y30.k`）拿到空结果直接跳过：不弹窗、不安装。

### 10. update-install-prompt — 已下载更新包安装提示

`y30.l$a.checkInstall(Context)` 在主界面启动时检查 `apk_store_info` 残留：若缓存里有比当前版本新的安装包，会弹「安装新版本」对话框。
整体禁用后不再读取、不再提示；残留的旧安装包文件保持原样，可手动清理应用缓存。

## 已知边界

- 品牌 / 活动开屏（如拜年纪）不做拦截；个别情况下可能随副作用提前退出，属于可接受结果
- 开屏资源预加载逻辑保留（只是不再触发），无副作用
- 信息流广告、视频贴片广告不在本工程范围
- 去更新检测后，设置里「检查更新」点击无响应（预期行为）

## 构建后自查（建议）

1. **回编一致性**：对补丁 dex 再跑一次 baksmali，与补丁前 smali 树 diff
   预期差异仅为：布尔字段默认值 `= false` 编码丢失、跳转标签按偏移改名、空行
2. **类数 / 方法数**：`baksmali list classes|methods` 与原版 dex 对比，数量一致
3. **包内核对**：替换后 zip 中仅目标 dex 变化，其余条目与原包逐字节一致
4. **安装前**：`apksigner verify` 与 `zipalign -c 4` 均通过

第 1、2、3 条是排查「回编损坏」的关键手段；数值不一致就说明回编链路有问题，不要安装。

> 注意：smali 回编产物**不保证字节级确定性**——同一棵 smali 树连续回编两次，dex 字节排列也可能不同
> （受类/方法处理顺序影响）。因此**不要**用 md5 对比两次构建的结果；正确判据是：
> 类/方法集合一致、回读 diff 无实质差异、剩余条目逐字节核对一致。

## 版本适配

规则锁定 9.15.0 的类与方法签名；换版本后按 README「适配其他版本」一节操作。
`expect_changes` 是安全阀：方法结构变化导致改动数对不上时，patcher 会直接报错退出。
