# AM++ 自改版 · 交接文档（新对话请先读这份）

> 最后更新：2026-11-13 14:10 CST ｜ 当前设备版本：mod36（双方案 + 折叠态防绘制） ｜ 当前分支：ampp-custom

## 1. 一句话现状

在 Apple Music 7.0.0(1614) 的免 root 嵌入版上做了歌词特效、Apple 风格设置页、横屏按钮布局、
旋转封面占位缓存、歌词换行背景光。

插入位置扫描（2026-11-12）结论是**能插**，光斑层已经按这个结论实现并上机验证：
`GlowLayer` 插在 `player_root` 的 `background_layers` 之后、z=0，画在宿主底图之上、封面/歌词/控件之下。
播放中缓慢呼吸，歌词换行时亮起一次。横竖屏、触摸穿透、旋转、暂停全部验证过，`gfxinfo` 0% janky。
详见 `AMPP-glow-layer.md` 与 `AMPP-insertion-scan.md`。

## 2. 环境

| 项 | 路径 / 值 |
|---|---|
| 工作目录 | `~/ampp-lab` |
| 源码 | `~/ampp-lab/AM-plus-plus`（分支 `ampp-custom`） |
| JDK 21 | `~/ampp-lab/tools/jdk21/Contents/Home` |
| Android SDK | `~/ampp-lab/android-sdk`（build-tools 37.0.0、platform android-37.0） |
| NPatch CLI | `~/ampp-lab/npatch.jar`（实际在 `/tmp/np.jar`，重启后需重新获取；v1.1.0-857） |
| 签名 | `ampp-jks.jks`（apksigner 用）+ `ampp-bks.bks`（NPatch 用，密码 `ampplab`，alias `ampp`） |
| 原始输入 | `origin-base.apk`、`orig/split_config.*.apk` |
| 设备 | Redmi Pad `26048RP6AC`，Android 16 / SDK 36，1880×3008 @450dpi |
| adb | `~/ampp-lab/android-sdk/platform-tools/adb`，serial `192.168.43.191:42793`（无线） |

## 3. 源码版本

- 分支 `ampp-custom`，基线 `388164c`（上游 Zennmn/AM-plus-plus main，v1.6.9 / versionCode 119）
- 关键提交：
  - `c8086d7` 歌词特效 / Apple UI / 横屏按钮布局
  - `0663887` 旋转封面占位缓存
  - `52f59df` 背景律动：驱动宿主 H/L/M 动画器
  - `65e029e` 节拍检测 WIP（AudioTrack，已验证拿不到数据）
  - `822700b` 换行光：改用 nativeLine 触发
  - `1b3baa8` 换行光开关
  - `8398808` 换行光收紧（半径 0.10、120ms）
  - `0cb2b0c` 插入位置扫描探针（诊断用，默认关闭）
  - `c6fcaa5` 播放页光斑层（GlowLayer）
  - `a848722` 光斑改边缘光环（距离场、平滑渐消、调淡）
  - `1fa9c3b` 柔光三项设置（强度/范围/呼吸，0 即关闭）
  - `3868a88` 设置顺序：换行律动光 + 三个柔光滑块移到歌词加粗下面
  - `db8c289` 歌词自动回弹（滑动离开 3 秒后回到正在播放的行）
  - `0399aba` 回弹只在播放中介入
  - `2cf9827` 对话框关闭后恢复播放页（PlayerSheetRestore）
  - `bdc659d` 动态背景双方案：A 封面网格 / B 极光幕帘
  - `616d780` 方案 A 按参考统计调参（已上机验证）
  - `43506cf` 折叠态三重防绘制 + 移除诊断探针
- 标签：`ampp-1.6.9-mod20` … `ampp-1.6.9-mod36`
- 回滚：`git checkout main`；回到某版：`git checkout ampp-1.6.9-modXX`

## 4. 产物

| 文件 | 说明 |
|---|---|
| `AMPP-v1.6.9-mod36-…-embedded.apks` | **当前最新 / 已安装**（双方案 + A 调参 + 折叠态防绘制，164 MB） |
| `AMPP-v1.6.9-mod34-…-embedded.apks` | A 按参考统计调参（中间版） |
| `AMPP-v1.6.9-mod33-…-embedded.apks` | **设备上正在运行的版本**（A/B 双方案，已验证） |
| `AMPP-v1.6.9-mod32-…-embedded.apks` | + 对话框后恢复播放页 |
| `AMPP-v1.6.9-mod31-…-embedded.apks` | + 歌词自动回弹 |
| `AMPP-v1.6.9-mod30-…-embedded.apks` | 光斑层设置顺序定版 |
| `AMPP-v1.6.9-mod29-…-embedded.apks` | 边缘光环 + 三项设置 |
| `AMPP-v1.6.9-mod28-…-embedded.apks` | 边缘光环版（无设置项） |
| `AMPP-v1.6.9-mod27-…-embedded.apks` | 首版光斑（5 个固定光斑） |
| `AMPP-v1.6.9-mod26-…-embedded.apks` | 上一版（换行光收紧、无光斑层） |
| `out/dev.amenhancer.module.apk` | 模块包（LSPosed 可用） |
| `AMPP-mod-report.md` | 功能改动全量说明（第 9–12 节是最新实验记录） |
| `AMPP-insertion-scan.md` | **插入位置扫描结论 + 生产配方 + 证据截图**（`shots/scan/`） |
| `AMPP-glow-layer.md` | **光斑层实现/调参/验证**（`shots/glow/`） |
| `AMPP-audio-beat-routes.md` | 音频/BPM 六条路线可行性评估 |
| `AMPP-archive-manifest.md` | 早期产物/工具链哈希 |
| 其余 `AMPP-v1.6.9-mod3…mod25` | 各阶段历史包，可用于回退对比 |

## 5. 工具与脚本

| 脚本 | 用途 |
|---|---|
| `repack.sh` | 发布打包：签模块 → NPatch embed → 签分片 → 产出 `.apks`（干净 base 87MB） |
| `tools/deploy.sh --fast` | 真机迭代：编译→打包→adb 安装→启动→日志（约 40s/轮） |
| `tools/connect.sh` | 无线 adb 配对/连接 |
| `tools/logs.sh` | 过滤模块日志 |
| `tools/tap-id.py` | 按 resource-id 抓控件坐标并点击（`dump <filter>` / `tap <id>`） |
| `tools/axml_add_configchanges.py` | 二进制 AndroidManifest 补丁（加 configChanges 等），支持 UTF-8/UTF-16 字符串池 |
| `tools/inject_sigblock.py` | 把原 APK 的签名块注入副本（NPatch 取原始签名用） |
| `tools/serve.sh` | 无 adb 时局域网下载 `.apks` |

## 6. 已实现功能

### 歌词特效（设置 → 歌词效果）
- 双向模糊（实时生效）、CJK 辉光、歌词效果优化（需重启）
- 边缘羽化：`max(28dp, 视口×8%)` smoothstep，乘进 alpha
- 已唱歌词消失：320ms ease-out 淡出（平板双栏路径）
- 歌词加粗：`isFakeBoldText` + `Typeface.BOLD`，实时读值
- **动态背景（mod33）**：方案 A＝封面派生网格渐变（对齐 LyricsBlossom 的 BackdropMeshLayer 行为），
  方案 B＝极光幕帘（自研）。设置 → 歌词效果 → 极光风格（方案 B）切换，默认 A。
  详见 `AMPP-visual-AB.md` 与 `analysis/lyricsblossom/REPORT.md`
- **对话框后恢复播放页（mod32）**：宿主的「添加到播放列表」选择器会收起播放页且不恢复（上游同样如此）；
  模块在对话框关闭后自动点一下迷你播放器把播放页带回来。10 s 窗口、只恢复一次，主动返回不触发。
  详见 `AMPP-mod-report.md` 第 17 节
- **歌词自动回弹（mod31）**：设置 → 歌词效果 → 歌词自动回弹（默认开）。宿主一拖动就脱离跟随且不会自己回来，
  模块在「当前行不在视口」且空闲 3 秒后逐帧走近并居中；暂停时不介入。详见 `AMPP-mod-report.md` 第 16 节
- **换行律动光（mod29 定版）**：`GlowLayer` 自绘层，插在 `background_layers` 之后、z=0；贴边一圈光环，
  距离场 + smoothstep 向内渐消，颜色取自封面主色。播放中按设置的节奏呼吸，换行时脉冲。
  三项设置：柔光强度 0–200%（默认 100）、柔光范围 0–40%（默认 18）、呼吸节奏 0–30s（默认 7），
  **任一为 0 即关闭**。旧的"向宿主 setArtwork 喂图"路径已删除
- 实现要点：透明度走 `RenderEffect` 颜色矩阵链（`LyricBlurEffectOwner.apply(..., alphaPercent)`），**不写 View.alpha**，避免与宿主逐词动画抢属性

### 设置界面
- Apple 风格：inset grouped 卡片、17/13pt、iOS 开关（51×31）、自绘滑块、强调色 `#FA233B`
- 组件在 `EmbeddedAppleStyle.kt` + `EmbeddedAppleSlider.kt`，`EmbeddedSettingsWidgets.kt` 全量委托

### 播放页（横屏）
- 翻译按钮移到歌词按钮左侧：**等中心节奏**（pitch = 歌词↔队列中心距）、按 intrinsic 比例缩放（79/101×1.02）、底色 inset 到邻居尺寸、队列图标 1.18 倍补重、溢出时弹窗上翻
- 竖屏保持宿主原样，位移清零
- 平板横屏隐藏 `list_favorite_icon`

### 其它
- `RotationGuard`：旋转期间跳过中间树写入
- `ArtworkRotationCache`：旋转时用上一帧封面填新 Activity 的空槽
- `ModuleSettingsRevision`：设置写入后 bump，渲染器重读 → 模糊/羽化/已唱/偏移/加粗实时生效

## 7. 关键技术发现（实测，避免重新踩坑）

### 7.1 播放页背景层结构
```
player_root (ConstraintLayout)
├── background_layers  = LyricsBackgroundLayerView（普通 View，不是容器）
│     内部：b/c 两张 Bitmap + 自己的模糊函数 b(...) + H/L/M 三个 ValueAnimator
├── motion_switcher（封面/标题）
├── player_fragments_host（歌词等内容）
└── mini_player
```
- 公开方法：`setArtwork(Bitmap)`、`setReducedEffects(boolean)`、`setSkipInvalidate(boolean)`
- 动画器：`H/L/M`，duration 120s/90s/70s，`repeat=INFINITE`，**宿主默认不启动**；启动后 pause 会被重新拉起
- 字段：`a=45.0 a0=3.5 W=72.0 b0=24.0 c0=72.0`（半径/缩放/位移相关）

### 7.2 可见性实测（最重要）
- 给 `setArtwork` 喂带光斑的帧：帧间差分 0.6–3.8（有变化）
- 把闪烁拉长到 1.6s 连拍 10 帧：中心与边缘亮度差 **≤1.3/255 → 肉眼不可见**
- 结论：**模块没有自己的画布**，喂给宿主的帧会被覆盖，做不出明显光斑

### 7.3 音频抓取（全部失败）
| 抓取点 | 结果 |
|---|---|
| `AudioTrack.write`（short[]/byte[]/ByteBuffer） | 0 包 |
| `MediaCodec.dequeueOutputBuffer` | 0 包 |
| `MediaCodec.getOutputBuffer/releaseOutputBuffer` | 0 包 |
- 播放确认：`dumpsys media_session` = PLAYING
- 原因：`libandroidappmusic.so` 用 **OpenSL ES**，音频在 native 层
- LyricsBlossom（`com.lyricsblossom.app`，8.1）用的是 `RECORD_AUDIO` + `FRAGMENT_SERVICE_MEDIA_PROJECTION` + `AudioPlaybackCaptureConfiguration` + `Visualizer.onFftDataCapture` + `FlashEnergy` + `TBPM`

### 7.4 旋转
| 方案 | 结果 |
|---|---|
| B（manifest 加 configChanges） | Activity 不重建了，但宿主不重排 → 竖屏错乱，否决 |
| A（快照遮罩） | 旧帧是横的、1s 后露黑，否决 |
| C（窗口 Fade transition） | 黑屏数秒，否决 |
| 封面占位缓存 | 采用（旋转后封面立即在位） |

### 7.5 其它坑
- `install-multiple` **不会重启宿主进程** → 必须 `am force-stop` 再启动，否则跑旧模块
- `deploy.sh --fast` 会把 `final/base.apk` 撑到 129MB（zipalign 展开去重），**发布必须走 `repack.sh`**
- NPatch 的 `-k` 只认 **BKS** 密钥库
- 改宿主清单必须改 **NPatch 输出里的清单**（不能拿原始清单覆盖，会冲掉 loader 改动）
- 自动化时设备息屏会打断流程；`uiautomator dump` 在分屏下会抓错窗口
- `/tmp/np.jar` 重启丢失，GitHub release 直连被墙，需 gh-proxy 慢速下载

### 7.6 叠加层 z 序（插入扫描实测，2026-11-12）
- `player_root`（ConstraintLayout）子序：`#0` 玻璃层、`#1 background_layers`、`#2/#3` Guideline、
  `#4 motion_switcher`、`#5` 双栏包裹（`tz=1.0`）、`#6 mini_player`、`#7` 覆盖层（`tz=7.0`）；竖屏 7 个子，`background_layers` 同样是 `#1`
- **Z 优先于子序**：`#5` 的 `translationZ=1.0` 让 index 6 的 z=0 层仍被压在下面；层设 `z=1000` 就能盖住一切
- 结论：自绘层插在 `background_layers` 之后即"底图之上、歌词之下"，**不要设 elevation/translationZ**
- 两栏本身没有不透明底色，所以全屏一层就能同时覆盖左右栏；歌词栏内 `^recycler_view_gradients:0` 是更窄的备选点
- 探针：`InsertionScanProbe.kt`（`ENABLED=false`），可用广播改目标/cycle/z/alpha/glow，不用重编译

### 7.7 光斑层实现要点（mod27）
- 插入：`indexOfChild(background_layers)+1`；LP 克隆兄弟类并给父容器实测像素尺寸（`MATCH_PARENT` 在 ConstraintLayout 会变 0dp→0×0）
- 重挂载：`player_root` 尺寸变化时重插一次；旋转后日志 `layer inserted at player_root#1 1880x3008`
- 触摸：`GlowView` 不可点/不可聚焦，命中返回 false → 事件继续派发给下层（实测点暂停有效）
- 性能：RadialGradient 按 (尺寸, 色相桶) 缓存；脉冲 20fps、静息 6fps；实测 0% janky / 50th 13ms
- 测试口：`am broadcast -a dev.ampp.glow.TEST -p com.apple.android.music --ef strength 0.8 --ei holdMs 18000`
  （`holdMs` 保持电平便于截图；`--ef decayMs 4000` 慢放包络；`--ez probe 1` 打印状态）

## 8. 已否决方案（不要重复尝试）
- 在模块内用 RuntimeShader 直接挂宿主背景层 → 输入为空、背景变黑
- ~~自绘 BloomView 插到 background_layers 后 → 被内容层遮挡~~ **该结论已被 2026-11-12 插入扫描推翻**：
  同样的位置 z=0 明明可见（见 7.6）。当年的真实失败点更可能是 ConstraintLayout 的 LayoutParams
  （`MATCH_PARENT` 被当成 0dp、无约束 → 0×0），而不是"内容层不透明"。下次自绘层必须克隆兄弟的 LP 类并给显式像素尺寸
- 启动宿主 H/L/M 做全局呼吸 → 停不下来、暂停仍闪
- Java 层抓 PCM → 三处全空

## 9. 未完成 / 下一步

1. ~~插入位置扫描~~ **已完成**；~~光斑层~~ **已完成（mod27）**：`GlowLayer` 上机验证通过，
   产物 `AMPP-v1.6.9-mod27-…apks`。细节与调参见 `AMPP-glow-layer.md`
   - ~~强度/大小/呼吸做成设置项~~ **已完成（mod29）**：歌词效果页三个滑块，任一为 0 即关闭
   - 待观察：收起态（迷你播放器）系统仍在后台按 6fps 绘制本层（被上层不透明页面盖住），
     `gfxinfo` 未见 jank；若在意耗电可再找宿主的 sheet 展开信号
2. **伴生 App（LyricsBlossom 形态）**（不必走：做法 B 已验证；仅在需要真实音频能量时作音频来源备选）：悬浮窗（SYSTEM_ALERT_WINDOW）+ MediaProjection 抓 AM 音频 → 节拍/能量 → 通过 localhost socket 喂给模块
3. **BPM/beatgrid 数据库**：评估结论是"方向对但顺序错"，应在有画布之后再做（详见 `AMPP-audio-beat-routes.md`）

## 10. 新对话接手步骤

```bash
cd ~/ampp-lab
cat AMPP-HANDOFF.md                     # 本文
git -C AM-plus-plus log --oneline -5     # 确认提交
~/ampp-lab/android-sdk/platform-tools/adb connect 192.168.43.191:42793
~/ampp-lab/android-sdk/platform-tools/adb devices
bash tools/deploy.sh --fast              # 确认能一键迭代
```

先确认三件事再动手：
1. 设备在线、`com.apple.android.music` 的 `lastUpdateTime` 是最新；
2. `git status` 干净、当前分支 `ampp-custom`；
3. 上一版行为正常（打开播放页，歌词/按钮/设置都在）。

然后从"第 9 节 第 1 条"开始。
