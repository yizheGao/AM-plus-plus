# LyricsBlossom 8.1 (`com.lyricsblossom.app`, versionCode 80006) — dynamic lyrics-background render path

Read-only analysis of `base.apk` (146,562,479 B). Nothing was installed on the device and the APK was not modified.

| artefact | size | SHA-256 |
|---|---|---|
| `base.apk` | 146,562,479 | `28b1bcfc5ada69b3aadaa4b235c353d62eb152528fe39c3b6165d547a753b8f2` |
| `classes.dex` | 1,105,540 | — |
| `lib/arm64-v8a/libmain.so` | 57,395,688 | — |
| `lib/arm64-v8a/libSDL3.so` | 2,041,352 | — |

Method: `unzip`, baksmali 2.5.2 (`classes.dex` → 957 smali files), LLVM `llvm-objdump` on the aarch64 ELF, Capstone 5.0.7 for ADRP/xref scans, Unicorn 2.1.4 for one native routine, PyCryptodome for cipher identification. Section `.rodata` of `libmain.so` has **VA == file offset** (start `0x15c000`); `.data.rel.ro` has **VA = file offset + 0x1000**. All addresses below are VAs unless marked "file".

The binary is the macOS-built C++ app itself: source paths such as `/Users/eplor/Developer/LyricsBlossom/src/Lyrics/Providers/NeteaseProvider.cpp` and `.../vcpkg_installed/arm64-android/lib/ossl-modules` survive in `.rodata`, and the renderer is a custom Skia + Vulkan stack (`SkiaRenderer`, `CoreRender`, `GlassKit`, `CRLayer`), with SDL3 used for the window and lifecycle only.

---

## 1. Audio path

### 1.1 Capture (Java, `com.lyricsblossom.app.AudioCaptureService`)

A foreground service created from `MediaProjection` data. `onStartCommand` builds the capture config through SDK-guarded bridge methods in `La/w0;`:

- `AudioPlaybackCaptureConfiguration.Builder(MediaProjection)` then `addMatchingUsage(0)`, `addMatchingUsage(1)`, `addMatchingUsage(14)` — `USAGE_UNKNOWN`, `USAGE_MEDIA`, `USAGE_GAME`.
- `AudioFormat.Builder().setEncoding(4 /* ENCODING_PCM_16BIT */).setSampleRate(0xBB80 /* 48000 */).setChannelMask(0xC /* CHANNEL_IN_STEREO */)`.
- Buffer size = `max(AudioRecord.getMinBufferSize(48000, 0xC, 4), 0x17700)` = at least **96,000 bytes** (0.5 s of 48 kHz stereo PCM16).
- `AudioRecord.Builder().setAudioFormat(...).setBufferSizeInBytes(...).setAudioPlaybackCaptureConfig(...)`.
- Worker thread named `lb-audio-capture` reads into a **4096-float** array (`new-array v9, 0x1000, [F`) with `AudioRecord.read([FIII)` and calls:

```smali
div-int/lit8 v0, v10, 0x2                  # frames = floats / 2
const v5, 0xbb80                           # 48000
invoke-static {v9, v0, v2, v5}, Lcom/lyricsblossom/app/MediaBridge;->nativeOnAudioFrames([FIII)V
```

`v2 = 2` (channels). So the JNI contract is:

```java
public static native void nativeOnAudioFrames(float[] samples, int frameCount, int channels, int sampleRate);
```

The Java layer also computes an RMS, but only for the log line `"feeding analyzer: RMS=%.5f"`, once every 2000 ms (`0x7D0`); it is not passed to native code.

### 1.2 The Visualizer path is waveform-only

`La/f9;` implements `Visualizer.OnDataCaptureListener`. `onFftDataCapture(...)` is an **empty method** (`return-void`). `onWaveFormDataCapture(...)` maps unsigned 8-bit bytes to float `(b-128)/128`, then calls `nativeOnAudioFrames(samples, len, 1, rate)` with `rate = captureRate/1000` when positive, otherwise `44100`. So the frequently cited `Visualizer`/FFT combination is a red herring: FFT is done in native code, and the Visualizer only supplies a mono waveform path (used when playback capture is unavailable).

### 1.3 Native ingest

`Java_com_lyricsblossom_app_MediaBridge_nativeOnAudioFrames` at `0x2821cfc`:

1. `GetFloatArrayElements` (JNIEnv vtable `+0x5e8`).
2. Locks `std::mutex` at `0x36be188`.
3. Appends to a `std::vector<float>` ring at `0x36be1e0`. Capacity guard: `(end-begin)>>10 <= 0x176`, i.e. **382,976 bytes = 95,744 floats ≈ 0.997 s of 48 kHz stereo**.
4. Stores `channels` at `0x36be198`, `sampleRate` (as `double`) at `0x36be1a0`.
5. `ReleaseFloatArrayElements(..., JNI_ABORT=2)`, then `condition_variable::notify_one` at `0x36be1b0`.

The consumer thread at `0x2822da8` waits on that condition variable, swaps the vector, computes `n = (bytes/4)/channels`, and calls `0x2a03eb0(samples, n, sampleRate, channels)`.

### 1.4 Downmix, window and hop

`0x2a03eb0`:

- If the sample rate changed by more than a small epsilon, it re-allocates a **2048-float mono ring** at object `+0xeb8` and resets `+0xed0`.
- For every input frame it sums all channels and divides by `channels` — a mono downmix.
- When 2048 samples are accumulated, it calls `0x282f474` (analysis), then `memmove`s the upper half down and sets the write index to 1024. **Window = 2048 samples (42.67 ms @ 48 kHz), hop = 1024 samples (21.33 ms), 50 % overlap.**

### 1.5 What is computed (native)

`0x282f474`:

- Rounds N up to a power of two (2048), computes `log2(N)` and builds the FFT tables (bit-reversal + twiddles) in `0x282f0b4`.
- **Automatic gain control** before the transform:
  - normal path: if `0 < env < 0.95`, multiply all samples by `gain` (`obj+0xb0`);
  - else if auto-gain flag `obj+0xac` is set: `peak = max|x|`; `env += (peak > env ? 0.3f : 0.01f) * (peak - env)`; `env = max(env, 0.001f)`; `gain = clamp(1/env, 0.1f, 30.0f)`.
  - The 0.3/0.01 coefficients are float literals `0x3E99999A` / `0x3C23D70A`; floor `0x3A83126F`; clamps `0x3DCCCCCD` and `30.0`.
- `0x282f834` is a radix-2 complex FFT: bit-reversal permutation, per-stage `sincosf` twiddles (`0x35ee390`), then `re²+im²` magnitude; it returns **four scalar values** in `d0..d3`.
- Six log-spaced bands are summed from a 7-entry edge table at `obj+0x90..0xA8` (edges clamped to `[0, N/2]`), then each band is normalized:

```
band = clamp( (20*log10(sum + 1e-6) - 30) / 120 , 0, 1 )
```

  (`20.0`, `-30.0`, `120.0`, `1e-6` are literal constants in the function).
- Four-band edge computation uses `exp2f` between 20 Hz and `min(Nyquist, 22528 Hz)` (`0x46B30000` = 22528.0), with 1/3-octave-style fractional steps.
- Results are pushed into a **32-slot history ring**:
  - 6 floats per slot at `obj+0x340 + (seq & 31)*24`;
  - 4 floats per slot at `obj+0xC4 + (seq & 31)*16`;
  - `seq` is an atomic `uint32` at `obj+0x2C4`.

So the handoff to the renderer is: **six 0..1 band energies plus four FFT-derived scalars, with ~0.68 s of history at 21.3 ms resolution**.

### 1.6 Beat / tempo

There is **no onset detector, no beat tracker and no tempo estimator** in `libmain.so`. Strings `onset`, `autocorr`, `comb filter`, `BPM`, `tempo` do not occur in native code. `TBPM` and `FlashEnergy` appear only in the obfuscated dex classes `a/Ra` and `a/C5`, i.e. as **ID3v2 frame names in the bundled media3 metadata reader**; they never reach the native visual pipeline. No `TBPM` xref exists in `libmain.so`.

---

## 2. Visual architecture

### 2.1 Window and GPU backend

`SDL_main` at `0x281ee28`:

- `SDL_Init`, `SDL_Vulkan_LoadLibrary`, `SDL_CreateWindow` with flags `0x10002001` = `SDL_WINDOW_VULKAN (0x10000000) | SDL_WINDOW_HIGH_PIXEL_DENSITY (0x2000) | SDL_WINDOW_FULLSCREEN (0x1)`.
- A `SkiaRenderer` object is constructed on the stack (`sp+0x560`), `initialize(SDL_Window*)` is called at `0x29fe780`; on success it logs `"SkiaRenderer initialized"` (string at `0x15c000`).
- Rendering is **Skia Ganesh on Vulkan**, not OpenGL ES: `VulkanBackend::presentFrame` / `VulkanBackend::flushAndSubmit` (`0x15c2ce`), `vkCreateSwapchainKHR`, `vkQueuePresentKHR`, `SwappyVk` frame pacing, and a persistent pipeline cache file `/skia_vk_pipeline.bin` (`0x176fcc`, class `LBPipelineCache`). `libGLESv2.so`/`libEGL.so` are linked but are used by the Java-side cover decoder and as a fallback path.
- The renderer owns a `VulkanBackend`, a `GPUImageManager` (texture cache) and a `CRFrameExecutor` (per-frame command recording) — all named in RTTI (`VulkanBackend`, `GPUImageManager`, `CRFrameExecutor`).

### 2.2 Retained layer tree, not immediate mode

The UI is a tree of `CRLayer` objects (`RTTI "7CRLayer"`), each with `tick(double)` (`CRLayer::tick(double)` appears as a lambda type). The compositor has a debug switch:

```
LB_CRLAYER_PASS layer=%p type=%s bounds=%.2f,%.2f,%.2f,%.2f alpha=%d blend=%d filters=%d backdrop=%d mask=%d
```

String at `0x15Fdec`; the `type=%s` argument is the layer's **C++ RTTI name** (the code loads `vtable[-1]` → `typeinfo+8`), and the gate is an environment variable read near `0x2AC2CC0`. This confirms per-layer bounds, alpha, blend mode, filter stack, backdrop and mask.

Layer classes present in RTTI (selection): `BackdropMeshLayer`, `CoverLayer`, `CoverShadowLayer`, `CoverBorderLayer`, `CoverContainerLayer`, `LyricsRootLayer`, `StaticLyricsContentLayer`, `MiniPlayerLayer`, `TitleBarIconLayer`, `MetaInfoLayer`, `ProgressBarLayer`, `LoadingSpinnerLayer`, `MarqueeLayer`, `MarqueeTextLayer`, `CompactControlLayer`, `WideControlLayer`, `WideTogglePillLayer`, `SourceRowLayer`, `UIHostLayer`, `FontPanelLayer`, `UIScaleLyricsPreviewLayer`, `LiquidGlassSettingsPageLayer`, `PrivacyDocumentLayer`, etc.

### 2.3 Frame loop and post effects

- `CRPostEffect` is the post-processing base class (`RTTI "12CRPostEffect"` at file offset `0x41E5B7`); `OrderedDitherEffect` (`RTTI` at `0x41E565`) is a concrete full-screen pass. Dither is also used inside the variable-blur pass (`Vulkan variable-blur dither ...`).
- Glass/backdrop passes: `CRBackdropVariableBlurVulkanPass`, `CRGlassBackgroundVulkanPass`, `CRGlassSDFTextureVulkanPipeline`, `CRGlassTexture3VulkanPipeline`, `CRGlassRuntimePipelines`. They operate on `texture3` / `texture4` render targets and use "rich highlights", i.e. specular highlights computed against the captured background (`More rich-highlight ...` strings).
- Variable blur uses a noise texture and a mask shipped inside the binary: `Renderer/VariableBlur/noise-rgba8-32x32.png` (`0x16F741`) and `Renderer/Portrait/variable-blur-mask.png` (`0x18280B`).
- Runtime SkSL effects are compiled for the glass UI: `shared glass runtime-effect compilation failed` (`0x160057`), `SDF round union requires its runtime effect`, plus per-frame FP16/FP32 uniform validation (`Glass FP16 uniform lane is not representable`, `Glass uniform ...`).

### 2.4 Animated cover frames

`AnimatedCoverDecoder` (Java) decodes animated GIF/WebP via `MediaCodec` into a `SurfaceTexture`, renders it in a **private EGL/GLES2 context** into an FBO, `glReadPixels` RGBA, and calls:

```java
nativeOnAnimatedFrame(ByteBuffer rgba, int width, int height)
```

The native side (`0x29BF410`) does not decode anything: it locks a mutex, copies the RGBA rows **bottom-up** into a shared frame buffer (`std::vector<uint8_t>` at `0x36C7D70`, row-by-row `memcpy` from row `h-1` downwards), then stores width `0x36C7DA0`, height `0x36C7DA4`, and increments a frame counter `0x36C7D98`. This is a staging buffer for the renderer's cover texture (the actual `vk` upload happens on the render thread — inference, the buffer is consumed elsewhere).

---

## 3. Shader inventory

### 3.1 Negative result: no app-authored plaintext GLSL/SkSL

Exhaustive string scan of `libmain.so` and `classes.dex`:

- Every `#version` occurrence belongs to Skia's SkSL compiler (`#version 300`, `#version 330`, `#version directive must appear before anything else`).
- All 48 occurrences of `half4 main(...)` are **Skia Ganesh built-ins** (blur kernels, gradient ramps, color matrix, HSL, circle/ellipse SDF coverage, dash effects, nine-patch, morphology, displacement, lighting, overdraw, etc.).
- The only GLSL in the APK authored by the app is the Java-side animated-cover blit (quoted below), not the background effect.

The app-specific background shaders are **not stored as text**. They are embedded as **AES-256-GCM encrypted modules** and decrypted at runtime.

### 3.2 The embedded shader pack

There are **52 `CoreRender::CRShaderProgram` entries**.

- Lookup table (program id → key pair): `0x3BA438`, 52 × 32 B, sorted by id. IDs: `0x01..0x21`, `0x22..0x27`, `0x30..0x32`, `0x34..0x37`, `0x3A..0x3C`, `0x3E..0x40` (enum gaps at `0x28-0x2F`, `0x33`, `0x38-0x39`, `0x3D`).
- Descriptor table: `0x35F4288` (VA; file `0x35F3288`), 52 × `0x78` B, binary-searched by the 16-byte key pair.
- 43 graphics programs (`kind=1`), 9 compute programs (`kind=4`: `0x22,0x23,0x24,0x25,0x26,0x27,0x3A,0x3B,0x3C`).
- Total encrypted module bytes: **357,791**. Smallest 55 B (`0x0B`), largest 78,448 B (`0x27`).
- **Every** program carries a 44-vertex, `44*12 = 528`-byte vertex buffer (its own copy; 132 floats, presumably `vec3` positions for a full-screen/rounded-rect mesh).

Descriptor layout (`0x78` bytes):

| offset | field |
|---|---|
| `+0x00` | key pair word A (u64) |
| `+0x08` | key pair word B (u64) |
| `+0x10` | `type` (u32): 1 = graphics, 4 = compute |
| `+0x14` | module plaintext size (u32) |
| `+0x18` | constant `0x78BFDBDDB33CEC92` (same in all 52 entries) |
| `+0x20` | 32-byte key seed |
| `+0x28` | 12-byte GCM IV |
| `+0x34` | 16-byte GCM tag |
| `+0x40` | last 4 bytes of the tag (overlaps the 16-byte tag window) |
| `+0x48` | module ciphertext pointer (relocated) |
| `+0x50` | module ciphertext size |
| `+0x58` | extra 64-bit seed |
| `+0x60` | vertex count = 44 |
| `+0x68` | vertex buffer pointer (relocated) |
| `+0x70` | vertex buffer size = 528 |

Program table (id, kind, type, encrypted size, vertices):

```
0x01 k1 t1  19664 B  44   0x02 k1 t1   5597 B  44   0x03 k1 t1   4400 B  44
0x04 k1 t1   2145 B  44   0x05 k1 t1   3600 B  44   0x06 k1 t1    979 B  44
0x07 k1 t1    311 B  44   0x08 k1 t1   2850 B  44   0x09 k1 t1   3961 B  44
0x0a k1 t1   2054 B  44   0x0b k1 t1     55 B  44   0x0c k1 t1     98 B  44
0x0d k1 t1    122 B  44   0x0e k1 t1   3316 B  44   0x0f k1 t1   7017 B  44
0x10 k1 t1   1531 B  44   0x11 k1 t1   1584 B  44   0x12 k1 t1   2073 B  44
0x13 k1 t1   1681 B  44   0x14 k1 t1   1797 B  44   0x15 k1 t1   5004 B  44
0x16 k1 t1    213 B  44   0x17 k1 t1    481 B  44   0x18 k1 t1    984 B  44
0x19 k1 t1   1317 B  44   0x1a k1 t1    635 B  44   0x1b k1 t1    223 B  44
0x1c k1 t1    366 B  44   0x1d k1 t1    474 B  44   0x1e k1 t1   1001 B  44
0x1f k1 t1   5503 B  44   0x20 k1 t1   3138 B  44   0x21 k1 t1  13824 B  44
0x22 k4 t4  22824 B  44   0x23 k4 t4   1092 B  44   0x24 k4 t4  24756 B  44
0x25 k4 t4   1092 B  44   0x26 k4 t4  72744 B  44   0x27 k4 t4  78448 B  44
0x30 k1 t1    603 B  44   0x31 k1 t1   1105 B  44   0x32 k1 t1   1361 B  44
0x34 k1 t1   1998 B  44   0x35 k1 t1    204 B  44   0x36 k1 t1   7871 B  44
0x37 k1 t1   4942 B  44   0x3a k4 t4  28928 B  44   0x3b k4 t4   1092 B  44
0x3c k4 t4   8104 B  44   0x3e k1 t1   1008 B  44   0x3f k1 t1   1118 B  44
0x40 k1 t1    503 B  44
```

### 3.3 Cipher and key ladder (why the modules cannot be quoted statically)

`0x2ABEC18` is the module unseal routine. The cipher is identified unambiguously:

- `0x2DA8740` returns the OpenSSL `EVP_CIPHER` descriptor at `0x3612128`. Its first words are `0x0385` (NID 901), key length `0x20` (32), IV length `0x0C` (12). NID 901 corresponds to `NID_aes_256_gcm` in OpenSSL's NID table (inference from the OpenSSL numbering; the key/IV lengths 32/12 match AES-256-GCM).
- `0x2DB4908` is `EVP_DecryptInit_ex(ctx, type, NULL, key, iv)`.
- `0x2DB4EC4(ctx, 9, 12, NULL)` is `EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, 12, NULL)`.
- `0x2DB3E80` is `EVP_DecryptUpdate(ctx, out, &outl, in, inl)` (its internal assert string is `../src/nssl-3.6.2-.../crypto/evp/evp_enc.c` / `EVP_DecryptUpdate`).
- `0x2DB4EC4(ctx, 0x11, 16, tag)` is `EVP_CTRL_AEAD_SET_TAG`.
- `0x2DB452C` is `EVP_DecryptFinal_ex`.

The AES key is **not** stored in the descriptor. It is produced by `0x2ABEF90(entry, out32)`, a pure computation that:

- reads `entry+0x00`, `+0x08`, `+0x58` and the first 12 bytes of the 528-byte vertex buffer at `entry+0x68`;
- mixes them with SplitMix64-style constants `0x9E3779B97F4A7C15`, `0xBF58476D1CE4E5B9`, `0x94D049BB133111EB`, plus `0xD6E8FEB86659FD93` and `0xA5A3564E27F8862B`;
- XORs the 12 vertex bytes with that 64-bit stream, then uses a byte-selected jump table (8 cases, table at file `0x41E48C`) to assemble the 32-byte key.

So the key ladder is: **encrypted vertex mesh → 12-byte selector → SplitMix64-derived 32-byte AES-256-GCM key → module plaintext**. The shader bytes and the mesh are mutually entangled; extracting them statically requires reimplementing/emulating this routine. (I emulated it in Unicorn; it yields a stable 32-byte key per program but the GCM tag does not verify under the straightforward descriptor-field mapping, which means the routine consumes additional runtime state or the descriptor fields are further transformed. I did not finish that last step; see §9.)

### 3.4 App-authored shader that *is* available (Java-side cover blit)

Exact strings in `classes.dex` (`com/lyricsblossom/app/AnimatedCoverDecoder`, smali `a/M.smali` lines 182/190):

```glsl
attribute vec2 aPos; attribute vec2 aTex; uniform mat4 uMatrix; varying vec2 vTex;
void main(){ gl_Position=vec4(aPos,0.0,1.0); vTex=(uMatrix*vec4(aTex,0.0,1.0)).xy; }
```

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;
void main(){ gl_FragColor=texture2D(sTex,vTex); }
```

Purpose: draw a `SurfaceTexture` (external OES) into the decoder FBO. Attribute `aPos` (vec2), `aTex` (vec2), uniform `uMatrix` (mat4), sampler `sTex`. This is the only non-Skia GLSL in the APK.

### 3.5 Skia runtime-effect shaders that the effect pipeline can use

These are Skia library sources (not app-authored), but they are the actual GPU building blocks available to the renderer; all are in `.rodata` as plaintext. Exact excerpts:

- **4×4 colour matrix + bias** (`0x15ECF4`) — the standard grading primitive:
  `uniform half4x4 m;uniform half4 v;uniform int unpremulInput; ... color = m * color + v;`
- **LUT / table colour filter** (`0x179E99`) — 3D-LUT-style grading with strength:
  `uniform half range;uniform shader inputFP;uniform shader table;half4 main(float2 xy){ half4 color=inputFP.eval(xy); half value=table.eval(sk_FragCoord.xy).a-0.5; return half4(clamp(color.rgb+value*range,0.0,color.a),color.a);}`
- **Two-stop gradient ramp** (`0x185694`):
  `uniform half4 start;uniform half4 end;half4 main(float2 coord){ return mix(start,end,half(coord.x)); }`
- **Multi-stop gradient** (`0x1B380F` / `0x1B6F..`): `uniform float4 thresholds[%d]; uniform float4 scale[%d]; uniform float4 bias[%d];` — binary-search over stops.
- **1-D blur / kernel convolution** (`0x16D7F4`): `uniform half4 offsetsAndKernel[kMaxUniformKernelSize]; uniform half2 dir; uniform shader child;` — used for separable blurs.
- **HSL** (`rgb_to_hsl` at `0x16DF3F`, `hsl_to_rgb` at `0x16A2A5`) and **luma** `sk_luma` with Rec.709 weights `(0.2126, 0.7152, 0.0722)`.
- **Working-colour-space shader** (`SkWorkingColorSpaceShader`) and the `toLinearSrgb` / `fromLinearSrgb` intrinsics — the renderer converts to linear before filtering.

### 3.6 Named programs / styles found in native strings

| string | offset | meaning |
|---|---|---|
| `GradientCS` | `0x1AC0A8` | only app-specific compute-program name found; almost certainly the gradient-field compute pass (inference) |
| `Backdrop.MeshSource` | `0x189BC1` | name of the mesh backdrop's source resource, looked up at `0x29C46D0` with length 19 |
| `Backdrop.BroadBlur` | `0x1CAAE8` | alternative backdrop style, looked up at `0x29C4754` with length 18 |
| `Portrait.ComposeArtwork` | `0x15C183` | artwork composition pipeline name |
| `CRGlass.Texture3.*`, `CRGlass.Texture4.Vulkan.Selector21`, `CRGlass.Background.Vulkan.NativeCurrentDestination` | — | glass render-target variants |
| `GlassKit.More.RegularV2.Diffuse`, `GlassKit.SettingsPreview.Regular.Appearance.24A5390f`, `GlassKit.ControlSliderRecipe.24A5390f` | — | glass UI recipes |

`GradientCS` is not a Skia built-in name and does not occur in any Skia string cluster; it sits among app strings.

---

## 4. Colour handling

- **No CPU palette extraction.** There is no k-means/quantiser/`vibrance`/`dominant colour` code path. All `palette` hits are libpng (`Invalid palette`, `Insufficient memory to process iCCP chunk`). No `kmeans`, `median cut`, `oklab`, or histogram code appears in `libmain.so`.
- The backdrop is generated on the GPU: `BackdropMeshLayer` (RTTI) renders a mesh whose source is `Backdrop.MeshSource`, and `GradientCS` is the compute program in the same subsystem. The mesh grid is built in code at `0x29C45E0-0x29C46A8`:
  - cell size candidates are `screenW / 645.0f` and `screenH / 1365.0f` (`0x44214000`, `0x44AA8000`), the smaller is rounded, clamped `>= 1`, and the grid is limited to **24** in each axis (`cmp w8,#0x18; csel ...,24`).
  - the result feeds a texture creation (`0x28B3CB4`) and the named source lookup.
  - **Inference:** the mesh is a screen-covering control grid (≤24×24) whose vertex colours are produced by `GradientCS` from the animated-cover texture; the renderer then samples the mesh.
- Colour math is half-precision FP16 on the Vulkan path: the glass uniform validator explicitly rejects non-representable FP16 lanes (`Glass FP16 uniform lane is not representable`, `Glass uniform color matrix is not representable as binary16`) and checks FP32 prefixes for finiteness. Skia working-colour-space objects (`SkWorkingColorSpaceShader`, `colorSpaceXform`, `SerializedColorSpaceInvalid`) are present; linear-light conversion is done by Skia (`toLinearSrgb`/`fromLinearSrgb`).
- Banding control: `OrderedDitherEffect` (RTTI) and a dedicated variable-blur dither texture; the dither pass is applied after gradients/blur.
- There is no evidence of a fixed palette or hue-rotation table; colours are computed per frame from the artwork field.

---

## 5. Motion / beat mapping

- Timebase: hop = **1024 samples = 21.33 ms** at 48 kHz; window 2048 = 42.67 ms. The analyzer publishes a new frame every hop.
- Features per frame:
  - 6 log-spaced band energies, each normalised by `(20*log10(sum+1e-6) - 30)/120` and clamped to `[0,1]`;
  - 4 additional FFT-derived scalars (returned in `d0..d3`; the code uses them as a 4-vector, clamped to `[0,1]` and multiplied by `obj+0xBC`);
  - 32-frame history of both vectors.
- Smoothing found in native code:
  - AGC envelope: attack coefficient **0.3**, release **0.01** per FFT frame, floor 0.001, gain clamped to **0.1…30.0**;
  - the 32-slot history is the only temporal buffer; per-band attack/decay is **not** in native code, so it must live in the encrypted shaders (they receive the history ring) — inference.
- There is **no** beat/onset/tempo detector and no BPM-driven parameter. `TBPM`/`FlashEnergy` are only ID3 tag keys in the dex metadata parser.
- `nativeScroll(float x, boolean dragging, float vx, float w, float h, float ...)` at `0x2821CC0` (Java `La/E8;`) feeds the lyrics scroller's position/velocity/gesture state into the same native state machine; it is independent of audio.

---

## 6. Config / UI surface

Preference files: `lyric_prefs.json`, `cover_prefs.json`, `lyrics_offsets.json`, `config.json`.

Keys / user-visible vocabulary found in `libmain.so`:

- layout: `lyricsControls`, `lyricsSettings`, `uiScaleSettings`, `liquidGlassSettings`, `miniMode`, `miniWindowWidth`, `miniWindowHeight`, `miniAlwaysOnTop`, `staticScroll`, `selectedTranslationLang`
- motion presets: `motionDetailSquare`, `motionDetailTall`, `motionSquareVideo1x1`, `motionTallVideo3x4`
- colour: `dynamicColor`, `dynamicColorAttr`, `dynamicStrokeAttr`
- actions: `shareImage`, `shareOverlay`, `saveToGallery`
- metadata/style: `remaster`, `lyricsSource`, `lyricsRootPath`, `appleAccountName`, `appleAuthBearer`, `appleAvatarUrl`, `appleMediaUserToken`, `neteaseCookie`, `qqmusiclight`
- style IDs: `Backdrop.MeshSource`, `Backdrop.BroadBlur`, `Portrait.ComposeArtwork`, `GlassKit.*`, `CRGlass.Texture3.*`, `CRGlass.Texture4.Vulkan.Selector21`

No user-facing "bloom", "particles", "aurora", "ripple", "spectrum" or numeric effect-preset names exist. The effect surface is deliberately small: a **mesh-gradient backdrop** with a **broad-blur** alternative, plus the Liquid-Glass control overlays.

---

## 7. Native extras

Notable strings in `libmain.so` (all `.rodata`, VA == offset):

- `SkiaRenderer initialized` `0x15C000`; `SkiaRenderer.initialize failed` `0x1CA991`.
- `VulkanBackend::presentFrame` `0x15C2CE`; `VulkanBackend::flushAndSubmit` `0x15C2EA`; `Swapchain: ` `0x15C308`; `[SwappyVk] ... present falls back to raw FIFO`.
- `LB_CRLAYER_PASS layer=%p type=%s ...` `0x15FDEC`; `LB_METAL_PASS_LABELS`; `LB_MORPH_PROBE`.
- `GradientCS` `0x1AC0A8`; `Backdrop.MeshSource` `0x189BC1`; `Backdrop.BroadBlur` `0x1CAAE8`.
- `shared glass runtime-effect compilation failed` `0x160057`; `SDF round union requires its runtime effect`; `Glass FP16 uniform lane is not representable`.
- `Renderer/VariableBlur/noise-rgba8-32x32.png`; `Renderer/Portrait/variable-blur-mask.png` `0x18280B`.
- `[morph] t=%.3f %s progress=%.3f opacity=%.3f blur=%.2f aux=(%.1f,%.1f,%.1f) released=%d platter=%.0fx%.0f r=%.0f glyph=%.3f lens=%.3f smooth=%.3f` — the player↔lyrics morph transition telemetry.
- `/tmp/lb_morph.log`, `/skia_vk_pipeline.bin`, `LyricsBlossom Engine`, `User-Agent: LyricsBlossom/1.0`.

`assets/` contains only fonts (`SF-Pro-Display-*.otf`, `PingFang.ttc`, `HiraginoSans-*.ttc`), four Liquid-Glass preview PNGs, and `cacert.pem`. **There are no shader files, LUTs, or effect JSONs in `assets/`.** Vector assets (SVGs) and the blur noise/mask PNGs are compiled into `.rodata` as byte arrays.

---

## 8. What is actually visible on screen (back to front)

Reconstruction from the layer/pass names, the backdrop grid code and the capture path. Items marked *(inference)* are not directly logged but follow from the component wiring.

1. **Mesh-gradient backdrop** — `BackdropMeshLayer` covers the window with a ≤24×24 control mesh. Its source is `Backdrop.MeshSource`; the colour field is generated by the `GradientCS` compute program from the current cover artwork (animated RGBA frames or the static cover), with a `Backdrop.BroadBlur` fallback that replaces the mesh by a wide blur when the mesh path is unavailable. Dither is applied to keep the gradient band-free.
2. **Animated cover art** — the RGBA frames staged by `nativeOnAnimatedFrame` are uploaded and composited into the cover stack: `CoverShadowLayer` → `CoverBorderLayer` → `CoverContainerLayer` → `CoverLayer`. On the full-player screen the cover sits above the mesh backdrop; the same mesh also picks up its colours, so the background moves with the artwork.
3. **Lyrics** — `LyricsRootLayer` → `StaticLyricsContentLayer` draws the timed text (TTML/AMLL and LRC/QQ/NetEase providers exist as `lyrics::*Provider` classes); `nativeScroll` supplies finger position/velocity; karaoke fill is per-syllable (the dex carries `karaoke`, `tlyric`, `tmh` tokens and the parser supports AMLL TTML).
4. **Player chrome** — `TitleBarIconLayer`, `MetaInfoLayer`, `ProgressBarLayer`, `MiniPlayerLayer`, `CompactControlLayer`/`WideControlLayer`/`WideTogglePillLayer`, `LoadingSpinnerLayer`, `MarqueeLayer`.
5. **Liquid-Glass overlays** — menus, settings pages and the "More" sheet are drawn as glass layers through `CRGlassBackgroundVulkanPass` / `CRGlassTexture3` / `CRGlassTexture4` with variable backdrop blur, SDF shapes, specular "rich highlights", and the `GlassKit.*` recipes. These are UI chrome, not part of the lyrics background.

The lyrics background itself is therefore a **GPU mesh gradient derived from the animated cover**, not a particle system, not a spectrum analyser and not a bloom/feedback chain. The audio bands modulate the mesh/gradient parameters inside the encrypted shader programs; the only audio-side shaping visible outside them is the AGC (0.3/0.01) and the dB normalisation.

---

## 9. Gaps and negative results

- **No app-authored GLSL/SkSL text for the background exists in plaintext.** If a reimplementation needs the exact math, it must come from the encrypted modules (see §3.3) or from behavioural capture on a device.
- The AES-256-GCM envelope and field layout are fully identified, but the 32-byte key derivation (`0x2ABEF90`) consumes the encrypted 44-vertex mesh and a jump-table decoder; my Unicorn reimplementation produced stable keys that did not verify the GCM tag under the straightforward descriptor mapping, so the last transformation is still missing. That is the single blocking step for a full shader dump.
- No `bloom`, `chromatic aberration`, `grain`, `vignette`, `feedback/afterimage`, `radial blur` or particle code was found in app strings. The only hits are third-party: SQLite `CREATE BLOOM FILTER` / `BLOOM FILTER ON`, DNG SDK `FixVignetteRadial`, HEVC `mDCV chromaticities`, ICU text `particle`. Treat any claim of those effects in this app as unsupported by the binary.
- The device was not run, so frame pacing, actual uniform values and the visible intensity curves were not measured. All numeric constants quoted above are static immediates from the disassembly.