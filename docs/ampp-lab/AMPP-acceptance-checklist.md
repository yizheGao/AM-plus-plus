# 明早验收清单（10 分钟）

## 1. 解锁平板后，第一条命令

```bash
cd ~/ampp-lab && bash tools/verify_ab.sh --install
```
它会：安装 mod34 → 打开播放页 → 截一张方案 A 的图到 `shots/visual-ab/`。
（脚本会先检查是否已解锁，锁屏时直接退出，不会乱点。）

## 2. 切方案 B 再看一次

设置 → 歌词效果 → 打开「**极光风格（方案 B）**」→ 回播放页 → 再跑：

```bash
bash tools/verify_ab.sh
```

## 3. 对照三张图

| 文件 | 是什么 |
|---|---|
| `shots/visual-ab/reference-lyricsblossom.png` | 参考软件实际运行 |
| `shots/visual-ab/A-scheme-cover-wash.png` | 方案 A（mod33 已验证版，mod34 更暗一点） |
| `shots/visual-ab/B-scheme-aurora-curtains.png` | 方案 B |

## 4. 需要你拍板的两件事

1. **方案 A 的亮度**：mod34 比 mod33 暗约 20%（对齐参考的 81/255）。
   如果觉得太暗，设置里「柔光强度」拉到 120–140% 即可，不用改代码。
2. **底部色带**：请描述一句现象——是"整条色带盖住封面"、"封面被切一半"，还是别的？
   我这边证据指向宿主自己的 mini player 材质色（`69,105,145`，比封面缩略图更饱和），
   不是模块写的；有你的描述我才能定向覆盖而不是瞎改宿主。

## 5. 已知限制

- 录屏没做：本机无 ffmpeg，`screenrecord` 产物无法解码核验，用多帧条带代替。
- 参考软件的 52 个 shader 是 AES-GCM 加密的，密钥推导差最后一步，所以方案 A 是**行为复刻**。
- 无歌词曲目上逐词事件不触发，能量密度会下降（只剩换行）。
