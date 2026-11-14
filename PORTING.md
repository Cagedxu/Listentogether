# 多加载器 / 多版本移植方案

## 目标矩阵

| 目标 | 加载器 | Minecraft | 状态 |
| --- | --- | --- | --- |
| 现有 | Forge | 1.20.1 | 可用，功能基准 |
| 新增 | Fabric | 1.20.1 | 联机验证通过：WAV/MP3/OGG/FLAC/M4A、双客户端同步、暂停位置、歌单持久化 |
| 新增 | Fabric | 1.21.1 | **已暂停**（用户要求先不做） |
| 新增 | NeoForge | 1.21.1 | **已暂停**（用户要求先不做） |

> 1.21.1 生态主流为 NeoForge；经确认，1.21.1 以 NeoForge 为目标。

## 当前工程结构

```text
musicmod/                 # Forge 1.20.1（原工程）
fabric-1.20.1/            # Fabric 1.20.1 独立构建
  src/main/java/com/musicmod/compat/   # Fabric 兼容层
  src/main/java/com/musicmod/network/  # Fabric 通道
```

Fabric 1.20.1 目前是独立项目，保留了 Forge 版源码的类结构，便于先验证行为；
后续抽出 `common` 模块时，再把音频、标签、歌单、歌词等无关代码上移。

## Fabric 1.20.1 已完成

- Loom + official Mojang mappings + Fabric API；
- Fabric 入口：`MusicModFabric` / `MusicModFabricClient`；
- 配置层从 `ForgeConfigSpec` 换成轻量 `ConfigSpec`（properties 文件）；
- 网络层改为 Fabric `s2c` / `c2s` 通道，18 个内部包重新分派；
- 事件改为 Fabric API：生命周期、玩家加入/离开、tick、命令、HUD、按键、连接/断开；
- 音频库改为 Fabric nested jar（`include`），jaad 核心内联但排除 SPI；保留 LGPL/Apache 许可证；
- 增加自定义 WAV reader，并改为下载完成后从文件播放，避开 mp3spi 对 WAV 的误判；
- 构建成功；专用服务器 + 开发客户端实际联机：WAV 12 秒完整播放、暂停位置保持、歌单持久化通过；
- MP3/OGG/FLAC/M4A 均完成下载与实际播放，格式识别为正确的 44.1kHz 立体声；
- 双客户端（SyncA / SyncB）同时连接，同一首歌同时开始、同时结束并同步续播。

## 下一步：1.20.1 Fabric 剩余验证

1. 验证上传界面、HUD、歌词在联机下的完整路径；
2. 验证非 OP 的只读操作与 OP 权限路径；
3. 1.21.1 按用户要求暂停，不继续。

## 1.21.1 计划

### Fabric 1.21.1

- 升级 Loom / Minecraft / Fabric API / Java 21；
- 网络从 1.20.1 channel API 改为 `CustomPacketPayload` + `StreamCodec`；
- 处理客户端渲染 API：`renderBackground` 签名、`ResourceLocation` 工厂、`GuiGraphics` 绘制差异；
- 用 version overlay 维护差异，不复制整个 GUI。

### NeoForge 1.21.1

- 网络改为 `RegisterPayloadHandlersEvent` / payload 注册；
- 事件、配置、客户端扩展点按 NeoForge 1.21.1 适配；
- 与 Fabric 1.21.1 共享 1.21.1 的客户端/网络差异层。

## 关键风险

1. **客户端类隔离**：Fabric 专用服务器已能启动，但后续仍需确保 GUI/按键/HUD 类不会被服务端加载。
2. **音频 SPI**：Fabric nested jar 的 ServiceLoader 行为需要实际播放验证；已为主 jar 添加合并的 SPI 文件，并让文件播放按扩展名直接选 reader。
3. **1.21.1 网络层**：1.20.1 与 1.21.1 的 payload API 差异大，不能直接复制。
4. **LGPL 合规**：Fabric 版以 nested jar 分发，优于 Forge 版合并类文件；仍需保留 notices 与源码地址。
5. **测试成本**：编译/启动通过不等于功能通过，四个目标都要实际联机验证。
