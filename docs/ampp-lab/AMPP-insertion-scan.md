# 插入位置扫描 · 实测结论（2026-11-12）

> 设备：Redmi Pad `26048RP6AC`，Android 16 / SDK 36，3008×1880（横屏双栏）/ 1880×3008（竖屏）
> 宿主：Apple Music 7.0.0 (1614) + AM++ 1.6.9 嵌入版（分支 `ampp-custom`，基线 `8398808`）
> 探针：`host-applemusic/.../hook/InsertionScanProbe.kt`（诊断代码，默认 `ENABLED = false`）

## 0. 结论（先看这段）

**能插，做法 B 成立，不需要伴生 App + 悬浮窗。**

- 自绘层放在 `player_root` 里、`background_layers` 之后（横屏实测 index 2 与 index 5 等价），
  会画在**宿主底图之上、封面 / 标题 / 歌词 / 控件 / 迷你播放器之下**。
- 竖屏、横屏双栏都验证过；`dumpsys media_session` = `PLAYING(3)`（1:28）下同样成立，歌词逐行滚动时光斑留在原位。
- 关键约束：**这一层的 z 必须保持 0**。这棵树里 Z（elevation / translationZ）优先于子序，
  双栏包裹层 `#5` 的 `translationZ = 1.0`，所以 index 6（本该在双栏之上）z=0 时仍然被压在下层。
- 歌词栏内还有一个更窄的可用点：`^recycler_view_gradients:0`（=容器 `container` 的 index 0），
  只覆盖右栏、同样在歌词文字之下。备选，不必要。

## 1. 判据与探针

品红全屏层（opaque `0xFFFF00FF`）+ 左上角黑方块 + 白字标签，逐 index 轮播，另有一档 `off` 基线：

- 品红出现在某块屏幕区域 → 这一层确实到达了屏幕，且该区域上面没有不透明兄弟；
- 品红区域里**还能看到字** → 文字画在我们之上（正是想要的位置）；
- 整块屏幕品红、内容全部消失 → 我们盖住了内容（负对照，用来证明判据本身有效）。

标签把当前目标名画在画面上，所以**每张截图自带上下文**，不依赖时间同步。
截图仍用 logcat 驱动（探针插入时打一行 `inserted <target>`），逐帧一一对应。

控制通道（不重启、不重编译）：

```bash
adb shell am broadcast -a dev.ampp.probe.CONFIG -p com.apple.android.music \
  --es targets "off,player_root:5,player_root:2,^recycler_view_gradients:0" \
  --ei cycleMs 8000 --ef z 0 --ei fillAlpha 255 --ez glow false --ez dump true
```

`targets` 语法：`id:index`；`id:top` 追加到末尾；`off` 为基线；`^id:index` 表示"id 的父容器"的内插。
`--ef z` 设该层 Z，`--ez glow true` 切到柔和光斑演示，`--ez dump true` 重新打印层级。

## 2. 实测数据

横屏 3008×1880，品红像素占比 = 截图里品红覆盖的面积比例：

| 目标 | z | 品红占比 | bbox | 读法 |
|---|---|---|---|---|
| `off`（基线） | — | 0.00% | — | 宿主原样 |
| `player_root:2` | 0 | **82.71%** | 0,0–3004,1876 | 全屏可见；封面 / 歌词 / 控件 / 迷你播放器全在上层 |
| `player_root:5` | 0 | 82.67% | 0,0–3004,1876 | 与 index 2 等价（中间两档是 Guideline） |
| `player_root:6` | 0 | 82.68% | 0,0–3004,1876 | **仍在双栏之下**——`#5` 的 tz=1.0 压过子序 |
| `player_root:5` / `:6` | **1000** | 96.93% | 全屏 | 负对照：盖住除系统导航栏和 2 个宿主按钮外的一切 |
| `^recycler_view_gradients:0` | 0 | 48.59% | 1400,0–2960,1876 | 只在歌词栏；栏内底图之上、歌词文字之下 |
| `player_root:2` + `glow` | 0 | — | — | 三团柔和暖光斑：底图之上、封面与歌词之下，歌词清晰（暂停态与播放中都拍过） |

证据截图（1/3 缩放，原图见设备现场）：

| 图 | 说明 |
|---|---|
| [01-baseline-off](shots/scan/01-baseline-off.png) | 基线：宿主自己的蓝色模糊底图 + 歌词 |
| [02-player_root-index2-flat](shots/scan/02-player_root-index2-flat.png) | index 2 全屏品红，内容全部在其上 |
| [03-player_root-index5-flat](shots/scan/03-player_root-index5-flat.png) | index 5，与上图等价 |
| [04-player_root-index6-z1000](shots/scan/04-player_root-index6-z1000.png) | 负对照：z=1000 时盖住一切 |
| [05-lyrics-pane-container-index0-flat](shots/scan/05-lyrics-pane-container-index0-flat.png) | 歌词栏内插：只染右栏，歌词在品红之上 |
| [06-player_root-index2-glow-landscape](shots/scan/06_player_root-index2-glow-landscape.png) | 光斑演示（横屏；这一帧是暂停在 0:00 时拍的） |
| [07-player_root-index2-glow-portrait](shots/scan/07_player_root-index2-glow-portrait.png) | 光斑演示（竖屏） |
| [08-player_root-index2-glow-playing](shots/scan/08-player_root-index2-glow-playing.png) | 光斑演示，播放中（`state=PLAYING`，1:28，歌词逐行滚动） |

内容没有被遮挡的量化旁证：把两帧 `off`（相隔 32 s，宿主背景律动在跑）与 `off`↔`player_root:2`
在同一"非品红像素"掩膜上比：噪声底 2.30% / 均值 1.00，信号 6.25% / 均值 3.84。
差值集中在**内容边缘与阴影**——封面卡的投影、字形抗锯齿原先合成在宿主底图上，现在合成在我们的层上，
这正是"层夹在底图和内容之间"应有的表现，不是遮挡。

## 3. 实测结构（dump 原文节选）

横屏（player_root 3008×1880，8 个子）：

```
player_root ConstraintLayout 3008x1880
  #0 - FragmentTabletGlassLayer          3008x1880     z=0
  #1 background_layers LyricsBackgroundLayerView 3008x1880   ← 宿主底图
  #2 lyrics_queue_thumbnail_top_guideline    Guideline vis=8
  #3 lyrics_queue_thumbnail_start_guideline  Guideline vis=8
  #4 motion_switcher ViewSwitcher        3008x1880 alpha=0.0  z=0
  #5 - FrameLayout                       3008x1880  tz=1.0     ← 双栏包裹（含两栏）
  #6 mini_player FrameLayout             3008x1880
  #7 - FrameLayout                       3008x1880  tz=7.0     vis=4
```

双栏包裹 `#5` 内部：

```
#0 player_fragments_host FrameLayout 1388x1835 elev=1.0
     #1 player_container ConstraintLayout 1388x1698   ← 左栏（封面 elev=23、标题、控件）
#1 FrameLayout 1565x1880
     #0 container ConstraintLayout 1565x1880 bg=ColorDrawable（实际透明）
          #1 recycler_view_gradients AlphaGradientFrameLayout 1565x1880
               #0 lyrics_main_content RecyclerView 1565x1880  ← 歌词
```

竖屏（player_root 1880×3008，7 个子）：`#0` 玻璃层、`#1 background_layers`、`#2/#3` Guideline 之后即内容区，
index 2 同样是"底图之上、内容之下"。子数随布局变化（7 / 8 / 9 含探针），**所以生产代码不能写死 2**。

## 4. 绘制顺序规则（这次扫出来的关键一条）

1. **Z 优先**：同父下先比 `getZ()`（elevation + translationZ），Z 大的后画；
   实测 `#5 tz=1.0` 让 index 6 的 z=0 层仍然被压住，`z=1000` 一插就盖住一切。
2. Z 相同才比子序（`addView(view, index)` 的位置）。
3. 因此"夹在底图与歌词之间"的写法是：**插在 `background_layers` 之后，且不设 elevation / translationZ**。

## 5. 生产实现配方（下一步直接用）

```kotlin
// 1) 定位（每次布局后幂等确保，宿主会重建子视图 / 旋转重建 Activity）
val root = decor.findViewById<ViewGroup>(id("player_root")) ?: return
val background = decor.findViewById<View>(id("background_layers")) ?: return
val index = root.indexOfChild(background) + 1

// 2) LayoutParams：克隆兄弟的参数类（宿主 ConstraintLayout 的 LP 是 R8 混淆过的，
//    只能用 (ViewGroup.LayoutParams) 构造保留正确类型，尺寸给父容器实测值）
val template = root.getChildAt(0).layoutParams
val lp = template.javaClass.getConstructor(ViewGroup.LayoutParams::class.java)
    .newInstance(ViewGroup.LayoutParams(1, 1)) as ViewGroup.LayoutParams
lp.width = root.width
lp.height = root.height
if (lp is ViewGroup.MarginLayoutParams) lp.setMargins(0, 0, 0, 0)

// 3) 插入：z 保持 0（不要 setElevation / setZ）
layer.layoutParams = lp
root.addView(layer, index, lp)
```

- 图层内容：自己画光斑（`RadialGradient` 或 AGSL），需要软边就自己 `RenderEffect` 模糊**本层**，
  不去碰宿主 `background_layers` 的 `setArtwork`；底图/文字都不动。
- 宿主在 index 0 有 tz=1 的双栏层、index 7 有 tz=7 的覆盖层——**任何 elevation 都会把光斑顶到歌词上面**，
  这是唯一容易踩的坑。
- 播放页收起（sheet 变迷你播放器）时不画；展开、旋转、切歌后按上面的幂等方式重新确保一次。
- 竖屏同样适用：`background_layers` 恒为 `player_root` 的 #1。

## 5.1 与旧实验（mod20 `BloomView`）的差异——不是"被遮挡"

第 8 节旧记录写的是"自绘 BloomView 插到 background_layers 后 → 被内容层遮挡"，本次实测否掉了这个解释：
同一位置 z=0 的层是可见的。差别在 **LayoutParams**：

- `ConstraintLayout` 里 `MATCH_PARENT` 会被当成 `MATCH_CONSTRAINT`(0dp)，而 0dp 的悬空子视图**没有约束时量成 0×0**，
  `addView` 会成功、`onDraw` 也"就绪"，只是画布是 0×0——日志里看着一切正常，屏幕上什么都没有。
- 本次探针的做法是：**克隆兄弟的 LP 类**（`(ViewGroup.LayoutParams)` 构造，保留宿主 R8 后的类），
  再把 `width/height` 设成父容器实测像素值。这样才会真的铺满。

也就是说：位置一直是对的，当年大概率是"插进去了但尺寸为 0"。

## 6. 边界与已知风险

- 探针层是诊断用的（全屏不透明品红），**不适用于发布**；正式层只画光斑，其余透明。
- 若宿主版本更新后给两栏加不透明底色、或把 `#5` 的 Z 结构改掉，层会被吞。自检办法：
  插入后下一次全局布局里确认 `layer.parent === root` 且 `layer.z == 0f`，并在调试日志里打一次。
- 光斑层在 `motion_switcher`（#4）之下：切歌时宿主的封面缩放/交叉淡入会盖住光斑，属预期。
- 层随 sheet 尺寸变化（`player_root` 收起时只有迷你播放器那么高），展开态才画。

## 7. 复现

```bash
cd ~/ampp-lab
# 1) 打开探针（ENABLED = true）后部署
sed -i '' 's/private const val ENABLED = false/private const val ENABLED = true/' \
  AM-plus-plus/host-applemusic/src/main/java/dev/amenhancer/module/hook/InsertionScanProbe.kt
bash tools/deploy.sh --fast

# 2) 进播放页（横屏双栏），设定扫描目标
adb shell am broadcast -a dev.ampp.probe.CONFIG -p com.apple.android.music \
  --es targets "off,player_root:5,player_root:2,^recycler_view_gradients:0" \
  --ei cycleMs 8000 --ef z 0 --ei fillAlpha 255 --ez dump true

# 3) 一步一张截图（脚本按探针日志触发，8 帧约 60 s）
adb logcat -c
python3 tools/scan_capture.py /tmp/scan1 8 1.0

# 4) 负对照 / 光斑演示
adb shell am broadcast ... --es targets "player_root:6,player_root:5" --ef z 1000
adb shell am broadcast ... --es targets "player_root:2" --ez glow true

# 5) 收尾
adb shell am broadcast ... --es targets "off"
```

像素统计口径：`screencap` PNG → RGB，品红判据 `R>170 && B>170 && G<90`，4 px 抽样。
截图尺寸 3008×1880，`screencap` 单帧约 2.6 s（无线 adb），所以 `cycleMs` 给到 8000 才够稳。
