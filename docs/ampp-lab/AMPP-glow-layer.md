# 播放页光斑层 · 实现与调参（mod27）

> 代码：`host-applemusic/src/main/java/dev/amenhancer/module/hook/GlowLayer.kt`
> 提交：`c6fcaa5`（tag `ampp-1.6.9-mod27`）｜ 前置结论：`AMPP-insertion-scan.md`

## 1. 结构

```
GlowLayer（object）
├── install(application)        生命周期的 resumed/paused 里挂载/卸载
├── bump() / pulse(strength)    换行触发入口（BackgroundPulse 转发）
├── frame (Runnable)            电平推进 + invalidate，脉冲 20fps / 静息 6fps
└── GlowView                    自绘 View：5 个光斑 + 1 层极淡光晕
```

挂载点与扫描结论一致：`player_root` 里 `background_layers` 的下一个位置，`z` 保持 0。

```
player_root
  #0 background_layers       ← 宿主自己的模糊底图
  #1 GlowView（本层，z=0）    ← 就在这里
  #2 motion_switcher …        ← 封面/标题/歌词/控件都在上面
```

## 2. 颜色与形状（mod28：边缘光环）

形状是**贴着视口一圈的环**：屏幕边缘最亮，向内到 `min(w,h)` 的 18% 处淡到 0。

- 遮罩用**到边界的距离场**生成：小图（宽 ≤480px）里逐像素算 `d = min(x, y, w−x, h−y) / band`，
  再过 `smoothstep` 得到 0→1 的 S 曲线，alpha = 1−t；小图用双线性放大铺满全屏。
- `smoothstep` 两端斜率为 0，所以**边框处和内缘都不会出现接缝或硬边**；四角由距离场自然处理，
  实测角落增量 33.2 vs 边中 33.6（不会角上过曝）。
- 颜色随 t 从暖白（边上）过渡到封面主色（内缘），读起来是"光"，不是色块。
- 遮罩只在 (尺寸, 色相桶) 变化时重建；每帧只有一次 `drawRect` + BitmapShader，比原来 6 个圆更省。

旧版（mod27）是 5 个固定光斑 + 光晕，已废弃。

## 2.1 颜色来源

- 取色：`BackgroundPulse` 仍在 `LyricsBackgroundLayerView#setArtwork` 上留一个 capture hook，
  拿 32×32 副本；按 `sat*255 + value*90` 加权求均值 → HSV 去饱和（×0.55，夹 0.10–0.55）、
  value 拉满 → 得到"从封面来的光色"。取不到时用暖白 `#FFE7C8`。
- 每个光斑的渐变是 **core(混 55% 暖白) → 中段 → tint → 透明** 四段，边缘靠 alpha 衰减而不是模糊，
  所以不需要 `RenderEffect`（避免整屏离屏模糊）。
- 半径一律按 `min(w,h)` 归一，横竖屏、双栏/单栏同一套坐标。

```
BAND_FRACTION = 0.18        // 环宽 = 0.18 × min(w,h)
MASK_MAX_WIDTH = 480        // 遮罩小图宽度上限
```

## 2.2 设置项（mod29）

「歌词效果」页里三个滑块，全部实时生效，**任一为 0 即关闭整层**：

| 设置 | key | 范围 | 默认 | 0 的含义 |
|---|---|---|---|---|
| 柔光强度 | `lyric_glow_strength_percent` | 0–200 % | 100 | 整层不画 |
| 柔光范围 | `lyric_glow_band_percent` | 0–40 % | 18 | 环宽为 0 → 整层不画 |
| 呼吸节奏 | `lyric_glow_breath_seconds` | 0–30 s | 7 | 平时不呼吸，只在换行时亮 |

总开关仍是「换行律动光」；它关掉时不读另外三项。每行右侧有 ↺ 复位到默认。
`GlowLayer` 每 200 ms 取一次设置快照（帧循环每秒读多次，避免每帧解码全部设置），
环宽变化时才重建遮罩；强度只是乘进 alpha，不重建。

呼吸公式是 **sin(2π·t / 周期)**；mod28 之前误写成 `sin(t / 周期)`，7 s 实际周期是 46 s。

## 3. 亮度模型

```
level = (播放中 ? AMBIENT_ALPHA × 强度 × breath : 0) + PULSE_ALPHA × 强度 × energy

breath = 0.5 + 0.5·sin(t / 7400ms)          // 缓慢呼吸
energy = 峰值后 exp(−(t−70ms)/260ms)         // 换行脉冲
AMBIENT_ALPHA = 0.05   PULSE_ALPHA = 0.30    // 100% 强度下的基准值
PULSE_ATTACK_MS = 120  PULSE_DECAY_MS = 420
```

- 暂停时 ambient 归 0，整层 `alpha=0`，循环自动停；再播放由下一次 attach/pulse 拉起。
- 电平 ≤0.004 时不画也不提交帧。

## 4. 性能

- `RadialGradient` 按 (宽,高,色相桶) 缓存，只在尺寸/换色时重建——原先每帧 6 个对象、20fps ≈ 120 次分配/秒。
- 帧率自适应：有脉冲 50ms，静息 160ms。
- 实测 `dumpsys gfxinfo`（播放中 16s，环版）：`Janky frames: 0 (0.00%)`，50th 14ms，90th 17ms。

## 5. 交互

`GlowView` 不是 clickable/focusable：命中的触摸返回 false，ViewGroup 继续派发给下层，
所以播放/暂停、滑歌词、点按钮都照常（实测点暂停 → `state=PAUSED(2)`）。

## 6. 测试入口（不重编译）

```bash
# 立即触发一次脉冲；holdMs 让电平保持住，便于 screencap（单帧约 2.6s）
adb shell am broadcast -a dev.ampp.glow.TEST -p com.apple.android.music \
  --ef strength 0.8 --ei holdMs 18000
# 慢放衰减包络（默认 260ms，抓不住）
adb shell am broadcast -a dev.ampp.glow.TEST -p com.apple.android.music \
  --ef strength 1.0 --ei holdMs 0 --ef decayMs 4000
# 打印一次状态（attach / 尺寸 / 开关 / 循环）
adb shell am broadcast -a dev.ampp.glow.TEST -p com.apple.android.music --ez probe 1
```

## 7. 验证记录（2026-11-12，Redmi Pad）

| 项 | 结果 |
|---|---|
| 横屏双栏可见 | hold 0.8 时整体 +8.5；边缘 40px 带 +36、向内 150–250px +15、**画面中心 +3**（≈不变） |
| 渐消平滑 | 横向剖面单调递减，相邻像素最大跳变 4.3/255、均值 0.28，无带状台阶 |
| 角落 | 角落增量 33.2 vs 边中 33.6，四角不过曝 |
| 竖屏可见 | 同样可见，位置随 `min(w,h)` 归一 |
| 换行触发 | `nativeLine` → `line pulse #4…#12`，间隔约 5s，与歌词行推进一致 |
| 暂停 | 整层消失（`state=PAUSED`），无残留 |
| 触摸穿透 | 播放/暂停、歌词滚动、按钮均正常 |
| 旋转 | 重挂载日志 `layer inserted at player_root#1 1880x3008`，无崩溃 |
| 关闭开关 | 脉冲被忽略、不绘制（回归过） |
| 帧耗时 | 0% janky，50th 13ms |

## 7.0 设置项验证（mod29 真机）

| 操作 | 结果 |
|---|---|
| 强度拖到 0% | `cfg=GlowConfig(on=false, strength=0.0, …)`，整层不画 |
| 范围拖到 0% | `cfg=GlowConfig(on=false, …, band=0.0, …)`，整层不画 |
| 呼吸拖到 0 s | `cfg=GlowConfig(on=true, …, breathMs=0.0)`；平时无光，hold 脉冲仍出现在边缘（edge +29.6，center −0.8） |
| 点 ↺ 复位 | 回到 100% / 18% / 7 s |
| 总开关关闭 | 三项都不读，脉冲被忽略 |

## 7.1 产物

| 项 | 值 |
|---|---|
| 发布包 | `AMPP-v1.6.9-mod29-AppleMusic7.0.0-1614-embedded.apks`（164 MB） |
| 包 SHA-256 | `8f3f938bf226c0f7eba7ded5175aee68128adfec888e71cd3d9d5b789e40fbd7` |
| 模块 SHA-256 | `112d38ec55317071f3150f29cf784a1a9d7ed1a11a96a616353f5c43999b30cf` |
| 证书 | `2feaaf03…c4109`（三包同证书，V3 通过） |

截图：`shots/glow/`（01 播放中呼吸、02 横屏脉冲、03 竖屏脉冲、04/05 发布包 A-B 对照）。

## 8. 调参位置

| 想要 | 改哪里 |
|---|---|
| 更亮/更暗 | 设置 → 柔光强度（或基准 `PULSE_ALPHA`/`AMBIENT_ALPHA`） |
| 环更宽/更窄 | 设置 → 柔光范围 |
| 呼吸快慢 | 设置 → 呼吸节奏 |
| 更硬/更软的边 | `buildRing()` 里的 `smoothstep`（可换成 `t*t*t*(t*(t*6-15)+10)` 五次平滑） |
| 更快/更慢的回弹 | `PULSE_ATTACK_MS` / `PULSE_DECAY_MS` |
