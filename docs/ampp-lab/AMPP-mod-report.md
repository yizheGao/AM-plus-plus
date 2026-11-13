# AM++ 1.6.9 自改版 · 归档说明（mod20 / 定版）

## 1. 产物

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `AMPP-v1.6.9-mod20-AppleMusic7.0.0-1614-embedded.apks` | 164 MB | `f18de8456ad9388c65900d0313457943385f2d31d0fa8057b5986a8e72368be8` |
| `out/dev.amenhancer.module.apk`（LSPosed 用模块） | 11 MB | `d41594a6c876fe8b31bc895256187d1ab9cdec9abd580fe74536ad4368cf616b` |

- `base.apk` = Apple Music 7.0.0(1614) + NPatch loader + 本模块；分片为原厂 `arm64_v8a` / `xxxhdpi` 重签
- 三包同证书 `CN=AMPP Lab`，SHA-256 `2feaaf039248284e365efd3e414aed8f94a59a1ab4b6d2f0d3225cc2693c4109`，V2+V3 通过
- 完整校验清单见 `AMPP-archive-manifest.md`

## 2. 源码归档

| 项 | 值 |
|---|---|
| 仓库 | `~/ampp-lab/AM-plus-plus` |
| 分支 | `ampp-custom` |
| 提交 | `c8086d7fce674abed47813390b3acc78731a4bc7` |
| 标签 | `ampp-1.6.9-mod20` |
| 上游基线 | `388164c`（Zennmn/AM-plus-plus main，v1.6.9 / versionCode 119） |

回滚到官方版：`git checkout main`；回到本版：`git checkout ampp-1.6.9-mod20`。

## 3. 功能改动

### 3.1 歌词特效（全部集中在「设置 → 歌词效果」）

| 开关 | 默认 | 实现 |
|---|---|---|
| 双向歌词模糊 | 开 | 原有能力；改为**实时读设置**，开关即时生效 |
| 歌词效果优化 | 关 | 宿主的解析/末词补偿/拆词辉光修补（仍需重启） |
| CJK 辉光 | 开 | 原有能力（仍需重启） |
| 边缘羽化 | 开 | `max(28dp, 视口×8%)` smoothstep，乘进 alpha |
| 已唱歌词消失 | 关 | 已唱行 320ms ease-out 淡出（平板双栏路径） |
| 歌词加粗 | 关 | 原生/导入字体均可；`isFakeBoldText` + `Typeface.BOLD`，实时读值 |
| 模糊半径偏移 | 0 | 实时生效 |

关键实现：透明度**不写 `View.alpha`**，而是折进模块独占的 `RenderEffect` 颜色矩阵链（`LyricBlurEffectOwner.apply(view, owner, radius, alphaPercent)`），避免与宿主逐词动画抢属性；羽化与淡出相乘。

### 3.2 字体

- 新增 `LyricsFontManifest.nativeSelected`：「Apple Music 默认字体」与已导入字体**共存**，切换不再删除导入文件（`clearFont()` 才删）
- `FontManifestPolicy.sanitize` 在 `enabled=false && nativeSelected` 时保留文件元数据

### 3.3 设置界面（Apple 风格）

- 新增 `EmbeddedAppleStyle.kt`：inset grouped 卡片（`#FFF`/`#1C1C1E`，12dp 圆角）、17/13pt 字号、iOS 开关（51×31、27pt 圆钮）、强调色 `#FA233B`/`#FF4A5D`
- 自绘 `AppleSliderRow`（4dp 轨道 + 13dp 白钮），替换 Miuix 滑块，颜色与开关同源
- `EmbeddedSettingsWidgets` 全部委托到 Apple 组件，签名不变，所有页面自动换肤
- 行高 `heightIn(min=44dp)`，修复小字被截断

### 3.4 播放界面（横屏）

- 翻译按钮从右栏左下移到**歌词按钮左侧**，与队列按钮同一排
- 尺寸按 `intrinsicWidth` 比例自适应（79/101 × `OPTICAL_BIAS`）
- 队列图标无底板，`LIST_OPTICAL_BIAS = 1.18` 补重
- 水平按**等中心节奏**：`pitch = queueCentreX - lyricsCentreX`
- 垂直按绘制内容中心对齐，`VISUAL_OFFSET_FRACTION = 0.080`（实测重心 dy = −3.8 px）
- 弹窗溢出时上翻（`TranslationsPopupOffsetHook` 由"仅平板双栏"放宽为"将溢出即上翻"）
- 竖屏完全不动，历史位移清零
- 平板横屏隐藏 `list_favorite_icon`

### 3.5 其他

- `RotationGuard`：`isChangingConfigurations` 期间跳过多栏布局写入，减少旋转掉帧
- 播放器底部按钮定位改用**本地化字符串资源**（`string/lyrics` / `string/player_queue`）比对 `contentDescription`，换语言不失效

## 4. 构建 / 打包 / 部署

```bash
# 1 构建
cd ~/ampp-lab/AM-plus-plus
JAVA_HOME=~/ampp-lab/tools/jdk21/Contents/Home ANDROID_HOME=~/ampp-lab/android-sdk \
  ./gradlew :app:assembleRelease

# 2 一次成型（签模块 → NPatch embed → 重签分片 → 打包）
bash ~/ampp-lab/repack.sh

# 3 日常真机迭代（更小更快，只用于调试）
bash ~/ampp-lab/tools/deploy.sh --fast
```

坑位记录：

- **NPatch CLI 的 `-k` 只认 BKS 密钥库**（BouncyCastle `BcKeyStoreSpi`）；JKS/PKCS12 报 `Wrong version of key store`。转换器：`tools/conv/Conv.java` → 产出 `ampp-bks.bks`
- `--fast` 路径用 zipalign 展开 NPatch 去重条目，`base.apk` 87 MB → 129 MB、`.apks` → 206 MB；**发布必须走 `repack.sh`**
- 设备：Redmi Pad（`yili` / 26048RP6AC），Android 16 / SDK 36，1880×3008 @450dpi；无线调试 `adb pair <ip>:<pairPort> <code>` → `adb connect`

## 5. 自适应说明（换设备不需要改代码）

| 参数 | 来源 |
|---|---|
| 翻译按钮缩放 | 两枚图标 `intrinsicWidth` 比值 × `OPTICAL_BIAS(1.02)` |
| 水平间距 | 实测 `歌词→队列` 中心距；无队列时回退 8 dp |
| 垂直偏移 | `0.080 × 歌词按钮高度` |
| 底色 inset | `(翻译盒 − 歌词盒) / 2` |
| 羽化高度 | `max(28dp, 视口高度 × 8%)` |

诊断日志（其它设备可据此校准）：

```
lyrics buttons: lyricsBox=124x124 translationsBox=219x129
  lyricsIntrinsic=79 translationsIntrinsic=101 scale=0.798 pitch=147.0 density=2.8125
```

## 6. 验证记录

- `:app:assembleRelease`、`:core:jvmTest`、`:host-applemusic:testDebugUnitTest` 全通过（结构回归断言已按新代码同步）
- 设备实测（横屏 3008×1880）：翻译胶囊 ≈0.798×（约 83px）、翻译→歌词 ~150px、歌词→队列 ~147px、垂直重心 dy = −3.8 px
- 弹窗：横竖屏点开均在按钮上方完整显示
- 日志确认各 feature 状态（`lyrics_parser: DISABLED - 歌词效果优化已关闭` 等）

## 7. 已知限制 / 未做

- 歌词效果优化、CJK 辉光、字体文件导入属安装期门禁，**改后需重开 Apple Music**；其余歌词特效与加粗已实时生效
- 设置页编辑器、在线歌词弹窗内部仍是 Miuix 控件（列表页已全 Apple 风格）
- 真机验证只覆盖 Redmi Pad 一台；其它设备依赖自适应参数，必要时用诊断日志校准
- 逐词上浮是宿主原生动画，模块不接管；本版只做行级淡出/羽化

## 8. 可继续的方向

1. 编辑器 / 弹窗按 Apple 风格继续替换
2. 逐词级特效（需要 hook 到 word TextView/Animator）
3. 竖屏播放器底部按钮也做同样的光学对齐
4. 把 `deploy.sh --fast` 的安装体积优化（差分推送或 only-base 方案）

## 9. mod21 追加：封面占位缓存

- 新增 `ArtworkRotationCache`：`isChangingConfigurations` 时抓取当前封面 ImageView 的 drawable（最长边 ≤720 px），新 Activity 两帧后用同一 id 的 artwork 视图填空槽；宿主自己的图片一到即覆盖。
- 缓存 2.5 s 过期，只填 `drawable == null` 的槽，不拦截宿主任何调用。
- 旋转相关的其它尝试（B 关重建 / A 快照遮罩 / C 窗口过渡）均已实测否决并移除，详见会话记录。
- 产物：`AMPP-v1.6.9-mod21-AppleMusic7.0.0-1614-embedded.apks`（`ed3a52e1…420d9ae4`）

## 10. 背景律动实验（默认关闭）

参考 LyricsBlossom 的"动态律动背景"，试了两版：

1. **v1 AGSL 着色器**挂到宿主 `LyricsBackgroundLayerView`（id `background_layers`）：挂载成功，但 `createRuntimeShaderEffect` 的输入内容为空，背景变纯黑 → 否决。
2. **v2 宿主层呼吸**（scale/alpha + 歌词行 bump）：挂载成功（日志确认 3008×1880），但该视图在当前双栏/竖屏布局下本身渲染为黑，且动画不触发重绘 → 肉眼无变化。

结论：宿主背景层不可复用，要复刻只能自绘背景（取封面 32×32 纹理 + AGSL warp/vignette + 歌词事件脉冲）。代码保留在 `BackgroundPulse.kt`，`ENABLED = false`。

## 11. 自绘背景层实验（默认关闭，未采用）

- 实现 `BloomView`：把封面缩成 32×32 纹理，AGSL 做低频噪声 warp + 暗角，歌词行变化时给一次能量脉冲。
- 过程中修掉两个真实崩溃：`background_layers` 实际是 `LyricsBackgroundLayerView`（普通 View，不能 addView）；封面槽 `fullplayerSongImage` 是 `MaterialCardView`（不是 ImageView），改为整视图栅格化。
- 结果：挂载成功、纹理就绪（日志可证），但播放页内容层自带不透明深色底，背景容器里的任何层都被遮住 → 肉眼不可见。
- 结论：该宿主下要让背景可见必须动内容层的叠放/透明度，风险高，暂停此方向。

## 12. 背景律动（mod22，已启用）

路径打通后的最终方案：

1. `hook LyricsBackgroundLayerView#setArtwork(Bitmap)` 捕获宿主自己的彩色封面（解决取色）；
2. 反射拿到宿主的三个呼吸动画器 `H/L/M`（duration 120/90/70 s，repeat=INFINITE，宿主默认不启动）；
3. 进入播放页时 `start()` 它们，歌词换行时 restart 一次做脉冲；
4. 背景由宿主自己的模糊管线 + 动画器渲染，我们只负责"启动"和"给脉冲"。

实测：背景呈专辑取色（深红），帧间差分 1.5–3.8，亮度在 81–85 之间缓慢起伏。产物 `AMPP-v1.6.9-mod22-AppleMusic7.0.0-1614-embedded.apks`。

## 13. mod27 前奏：插入位置扫描（2026-11-12）

目标：确认能否在宿主底图之上、歌词之下插自绘层（决定走模块内还是伴生 App + 悬浮窗）。

方法：`InsertionScanProbe`（诊断探针，默认 `ENABLED=false`）把不透明品红层逐 index 轮播插入
`player_root`，另加 `off` 基线；品红可见 = 层到达屏幕，品红里字仍清晰 = 字在层之上。
目标 / cycle / z / alpha / glow 全部走广播，不重编译。

结果（横屏 3008×1880）：

| 位置 | z | 品红覆盖 | 结论 |
|---|---|---|---|
| `player_root:2` / `:5` | 0 | 82.7% | 底图之上、封面/歌词/控件之下 ✅ |
| `player_root:6`（本该在双栏之上） | 0 | 82.7% | 仍被压住 —— `#5` 的 `translationZ=1.0` 优先于子序 |
| `player_root:5` | 1000 | 96.9% | 负对照：盖住一切 |
| `^recycler_view_gradients:0`（歌词栏内） | 0 | 48.6%（仅右栏） | 栏内底图之上、歌词之下 ✅ |

结论：**能插，做法 B 成立，不需要伴生 App**。绘制顺序规则是 **Z 优先于子序**，
所以插入点取 `indexOfChild(background_layers)+1` 且**不要设 elevation/translationZ**。
同时推翻第 11 节"被内容层遮挡"的判断：真实失败点应是 ConstraintLayout 的 LP（`MATCH_PARENT`→0dp、无约束→0×0）。

竖屏同样成立（`background_layers` 恒为 `player_root` 的 `#1`），播放中歌词滚动时也成立。
详见 `AMPP-insertion-scan.md`，证据截图在 `shots/scan/`。

## 14. mod27：播放页光斑层（已启用）

插入扫描（第 13 节）证明可以自绘，本版把它做成了真功能。

| 项 | 实现 |
|---|---|
| 挂载 | `GlowLayer` 插入 `player_root` 的 `indexOfChild(background_layers)+1`，z 恒为 0 |
| 渲染 | 5 个径向柔光斑 + 1 层极淡光晕，颜色取自封面主色（HSV 提亮去饱和，混 55% 暖白） |
| 动效 | 播放中 7.4 s 缓慢呼吸；歌词换行 70 ms 起、260 ms 指数衰减的亮度脉冲 |
| 开关 | 沿用「歌词效果 → 换行律动光」，实时读值；关掉整层透明 |
| 性能 | 渐变按 (尺寸, 色相桶) 缓存；脉冲 20 fps、静息 6 fps；`gfxinfo` 0% janky、50th 13 ms |
| 交互 | 层不可点/不可聚焦，事件穿透到宿主控件 |

同时删掉了旧路径：不再往 `LyricsBackgroundLayerView#setArtwork` 喂带光斑的帧（那条路只有 ≤1.3/255 的变化）。
`BackgroundPulse` 现在只做两件事：留一个 32×32 封面副本给光斑取色、把换行信号转发给 `GlowLayer`。

实测（Redmi Pad 26048RP6AC，Apple Music 7.0.0/1614）：

- 横屏双栏：均值亮度 110.9 → 120.3（hold 0.8 脉冲），歌词文字不受影响
- 竖屏：同样可见，位置随 `min(w,h)` 归一
- 换行触发：`nativeLine` → `line pulse #4…#12`，间隔约 5 s，与歌词行推进一致
- 暂停：整层消失；旋转：重挂载 `player_root#1 1880x3008`；无崩溃
- 触摸：点播放/暂停生效（`state=PAUSED(2)`）

产物：`AMPP-v1.6.9-mod27-AppleMusic7.0.0-1614-embedded.apks`
（164 MB，SHA-256 `c4c508ff7ff00163ef7286b55b6ca4e37906104033a729fdc72c33a4783ac3e4`）
细节、调参位置与测试广播见 `AMPP-glow-layer.md`。

## 15. mod29 / mod30：柔光三项设置与顺序

### 15.1 顺序（mod30）

「歌词效果」页最终顺序：

```
双向歌词模糊 → CJK 长尾歌词动画 → 歌词效果优化 → 边缘羽化 → 已唱歌词消失 → 歌词加粗
→ 换行律动光 → 柔光强度 → 柔光范围 → 呼吸节奏 → 歌词模糊半径偏移
```

### 15.2 设置项（mod29）

「歌词效果」页新增三个滑块，实时生效，任一为 0 即关闭整层：

| 设置 | 范围 | 默认 | 0 的含义 |
|---|---|---|---|
| 柔光强度 | 0–200 % | 100 | 整层不画 |
| 柔光范围 | 0–40 % | 18 | 环宽为 0 → 整层不画 |
| 呼吸节奏 | 0–30 s | 7 | 不呼吸，只在换行时亮 |

实现要点：

- 三个 key 进 `ModuleSettingsSchema`（`lyric_glow_strength_percent` / `_band_percent` / `_breath_seconds`），
  旧存储缺键时走默认值，无需迁移；结构断言测试同步更新
- `GlowLayer` 每 200 ms 取一次设置快照，环宽变化才重建遮罩，强度只乘 alpha
- 呼吸公式修正为 `sin(2π·t/周期)`：此前按弧度写，7 s 实际是 46 s
- 总开关仍是「换行律动光」；关掉时不读另外三项

真机验证：强度 0 / 范围 0 都得到 `on=false`；呼吸 0 时 `breathMs=0` 且换行脉冲仍出现在边缘
（edge +29.6、center −0.8）；↺ 复位回到 100% / 18% / 7 s。

产物：`AMPP-v1.6.9-mod29-AppleMusic7.0.0-1614-embedded.apks`
（164 MB，SHA-256 `8f3f938bf226c0f7eba7ded5175aee68128adfec888e71cd3d9d5b789e40fbd7`）

## 16. mod31：歌词自动回弹

宿主的行为是：用户一拖动歌词列表就脱离跟随，之后换行也不回来。本版补上一个开关型回弹。

实现（`LyricBlurViewSession`，与歌词模糊共用同一套状态与逐帧循环）：

| 环节 | 做法 |
|---|---|
| 触发条件 | 每帧 `preDraw` 里看「当前高亮行还有没有绑定行在场」；不在场、`scrollState==0`、且正在播放 → 开始计时 |
| 延迟 | 3 000 ms（连读几行仍算有意操作） |
| 回弹动作 | 逐帧 `scrollBy(0, ±height/3)` 走近 → 到达后再 `smoothScrollBy` 居中 |
| 为什么不用 adapter position | 宿主会插入 instrumental 间隔行，序号与 adapter 位置不等价；按可见行序号判方向、逐步走，不依赖映射 |
| 为什么不用宿主钩子 | 实测这版 `setScrollState` 与 `OnScrollListener` 都不触发，改为用现有的逐帧行观察 |
| 取消 | 任何新的触摸 / 滚动 / seek / 文档替换 / 再次滑动都会取消 |
| 暂停 | 不介入（`AudioManager.isMusicActive`，500 ms 缓存），暂停时用户可以安静读词 |
| 设置 | `lyric_auto_return_enabled`（默认开），关掉即完全不介入 |

实测（Redmi Pad，Apple Music 7.0.0/1614）：

- 播放中滑动离开：`begin anchor=[7]` → `step=1 visible=10..15` → `centred after 1 step(s)`，当前行回到顶部高亮
- 暂停时同样滑动：零介入
- 开关关闭：零介入

产物：`AMPP-v1.6.9-mod31-AppleMusic7.0.0-1614-embedded.apks`
（164 MB，SHA-256 `0f1560d46a9c9105997ae4bc8577e0319844072e4d618fddce7a57b8768cd37c`）

## 17. mod32：对话框关闭后恢复播放页

**现象**：播放页 ⋮ →「添加到播放列表…」→ 播放页被收起，选择器关掉后停在下面那层页面，不会回来。

**归属**：宿主行为。用未改动的上游 `388164c` 构建模块、装在同一份 host 上复现，现象一致；两版只差模块代码。

**机制**：选择器是同一 `MainActivity` 的对话框窗口（`ty=APPLICATION`、`TRANSLUCENT`、`DIM_BEHIND`），
不是新 Activity；它弹起时宿主把 player sheet 收掉，关闭后没有任何恢复逻辑。

**修法**（`PlayerSheetRestore`，不碰宿主菜单/对话框）：

| 环节 | 做法 |
|---|---|
| 检测弹窗 | 每 350 ms 看 decor 的 `hasWindowFocus()`：失去焦点=弹窗打开，恢复=关闭 |
| 判断“当时在看播放页” | `player_root` 的 `getGlobalVisibleRect` 高度 ≥ 屏幕长边 45% |
| 恢复动作 | 焦点恢复 250 ms 后若播放页仍不可见，就点迷你播放器（`performClick`，无监听时补一次合成点按） |
| 护栏 | 只在 10 s 窗口内、只恢复一次；用户主动返回（无对话框）不触发；切后台会重置状态 |

**实测**：⋮ → 添加到播放列表 → 返回 → 播放页自动回来（日志 `player sheet restored after dialog`）；
直接按返回收起播放页则保持关闭，日志为零。

产物：`AMPP-v1.6.9-mod32-AppleMusic7.0.0-1614-embedded.apks`
（164 MB，SHA-256 `79e3d22148dcdfcf037712b1a21a37982acbc4c24892c2910ba477cd18326841`）

## 18. mod33：动态背景双方案

参考对象是平板上安装的 LyricsBlossom 8.1，先实际运行观察（10 帧序列 + 时域方差图 + 像素采样），
再用其反编译结论校正，最后实现两套独立绘制路径：

| | 方案 A（默认，还原） | 方案 B（自研） |
|---|---|---|
| 形体 | 封面派生网格渐变（11×8，双线性放大） | 4 条竖直极光幕帘 |
| 颜色 | 面积平均采样 + 向主色混合 0.62 | A 的底色 + 幕帘用封面色渲染 |
| 运动 | 格间相位慢漂移，能量 +6% | 两组不可通约正弦漂移，alpha ≤0.38 |
| 观感 | 安静、单色相、像"暗色封面底" | 有方向性的流动光，仍然克制 |
| 切换 | 设置 → 歌词效果 → 极光风格（方案 B） | 同一开关 |

清理：删除边缘距离场光环、5 个固定光斑、+22% 全局闪亮；收起态（mini player）不再绘制，
避免把底栏涂成整条色带。参考软件实测：无粒子/bloom/反馈/频谱/beat 检测，背景就是网格渐变 + dither。

产物：`AMPP-v1.6.9-mod33-AppleMusic7.0.0-1614-embedded.apks`
（164 MB，SHA-256 `04fdfc650083c36753ca0726122fc38e8831b521fe414751e5d8598db4cd0e9d`）
