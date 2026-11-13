# 获取音频数据 / BPM 的技术路线评估（Android / AM 嵌入模块）

## 0. 已确证的事实（本机实测，2026-11-11）

| 事实 | 证据 |
|---|---|
| AM 正在播放时，Java 层拿不到任何 PCM | `dumpsys media_session` state=PLAYING；`AudioTrack.write` 三个重载 + `MediaCodec.dequeue/getOutputBuffer/releaseOutputBuffer` 全部 0 包 |
| AM 的音频走原生栈 | ABI split 中 `libandroidappmusic.so` 含 `OpenSLES` 符号 |
| 宿主未禁止被捕获 | base manifest 的 `<application>` 无 `allowAudioPlaybackCapture="false"`（targetSdk 36，默认允许） |
| 同机 LyricsBlossom 能抓到 AM 音频 | 其 APK 声明 `RECORD_AUDIO` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`，dex 含 `AudioPlaybackCaptureConfiguration` / `setAudioPlaybackCaptureConfig` / `Visualizer.onFftDataCapture` / `FlashEnergy` / `TBPM` |
| LyricsBlossom 在本机 AM 播放时确实闪烁 | 用户实机确认 |

## 1. 路线矩阵

| # | 路线 | 数据 | 可行性 | 工程量 | 风险 |
|---|---|---|---|---|---|
| A | 伴生 App：MediaProjection + AudioPlaybackCapture → IPC 给模块 | 真实 PCM，可算能量/BPM | **高（已被 LyricsBlossom 证明）** | 中（新 App + IPC） | 需用户授权屏幕/音频捕获；前台服务通知 |
| B | 进程内 native hook（OpenSL ES / 解码器） | 真实 PCM | 中低 | 大（native 注入 + 符号解析） | 崩溃/被检测风险高 |
| C | Java hook（AudioTrack / MediaCodec） | —— | **不可行（已证伪）** | 小 | 无 |
| D | 歌词时间戳（逐词/逐行 onset） | 节奏点，非真实能量 | 高 | 极小（已有 hook） | 快歌密集段偏密；非真 BPM |
| E | BPM 元数据 / 外部曲库（TBPM、AcousticBrainz、Spotify audio-features） | 静态 BPM + 段能量 | 中 | 中（曲目识别 + 网络 + 密钥） | 首次播放延迟；曲库覆盖率 |
| F | 其它系统 API（MediaSession 位置、音量、音频焦点） | 只能推相位，非能量 | 低价值 | 小 | 无法反映真实鼓点 |

## 2. 推荐组合

1. **渲染层先做**：基础缓慢流动 + 局部光斑，对外只暴露 `pulse(strength: Float)`，与节奏源解耦。
2. **节奏源优先级**：A（真 BPM/能量）→ D（歌词 onset，零成本兜底）→ E（可选补充）。
3. **B 只在 A 被用户否掉时再评估**；C 不再尝试。

## 3. A 路线架构

```
伴生 App（独立应用，拥有 RECORD_AUDIO + MediaProjection）
  MediaProjection token → AudioPlaybackCaptureConfiguration
  AudioRecord(44.1k / 16bit / mono)，10 ms 帧
    ├─ RMS 能量包络
    ├─ 低频带能量（40–150 Hz，一阶低通或 64-bin FFT）
    └─ 起音检测：局部均值 + k·std，refractory 180 ms
  BPM：inter-onset interval 直方图取众数，8 拍滑动平滑
  IPC：UDP 127.0.0.1:<port>，事件 {t, energy, beat, bpm}（<200 B）
                ↓
AM 进程内的模块
  本地 socket 接收 → 平滑 → BackgroundPulse.pulse(strength)
```

延迟预算：音频采集 20–40 ms + 检测 30 ms + IPC 5 ms ≈ **60–80 ms**，用于视觉闪烁足够。

## 4. 验收方法

- 已知 BPM 曲目（120 BPM）估计误差 ≤ 3%
- 暂停后 10 s 内不得再产生事件
- 切歌、切后台、断 IPC 均不得崩溃，IPC 超时自动回退到 D

## 5. 下一步建议

1. 先实现渲染层（不依赖音频），用 D（歌词 onset）驱动，验证视觉；
2. 再决定是否投入 A：最小验证只需要一个 50 行的 capture App 打印 RMS/BPM（LyricsBlossom 已证明可行，这一步主要是自建链路）。
