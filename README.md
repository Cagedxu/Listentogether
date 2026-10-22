# 一起听 (Listen Together) — Forge 1.20.1

> 作者：deepseekharness 和 joycode

面向 Minecraft **Forge 1.20.1** 的全服同步音乐播放器：服务器统一播放，所有在线玩家同步收听同一首歌。

## 环境要求

- Minecraft 1.20.1
- Forge 47.x
- Java 17

客户端和服务端都需要安装本模组，且版本必须一致。

## 构建

```bash
./gradlew build
```

Windows：

```bat
gradlew.bat build
```

产物：`build/libs/musicmod-1.1.0.jar`

## 安装

1. 安装 Forge 1.20.1（47.x）；
2. 将 `musicmod-1.1.0.jar` 放入客户端和服务端的 `mods/`；
3. 启动服务器，歌曲目录默认生成在 `config/musicmod/songs/`；
4. 把 MP3 / OGG / FLAC / WAV / M4A 等歌曲放入该目录，执行 `/music rescan`；
5. OP（权限等级 2）使用 `/music play <歌名>` 开始播放。

## 功能

- 全服同步播放、暂停、停止、上一首 / 下一首、进度跳转；
- 英语界面与英文聊天/命令反馈（English UI/localization）；
- 服务器共享歌单：新建、重命名、删除、添加 / 移除歌曲、按歌单顺序播放；
- 每个玩家本地内置「收藏」与「最近播放」；
- 歌曲标签、封面、内嵌歌词与同名 `.lrc`；
- HUD 歌词、详情页滚动歌词、双语翻译、点击歌词跳转；
- 窗口化播放器界面与经典界面；
- 顺序 / 单曲循环 / 随机播放；
- OP 上传音乐到服务器（无图形环境时使用游戏内路径输入）；
- 服务器无人时可配置继续 / 暂停 / 停止。

## 常用命令

所有人：

```text
/music list [页码]
/music nowplaying
/music volume <0-100>
/music playlist
/music playlist show <歌单名>
```

OP（等级 2）：

```text
/music play <歌名>
/music pause
/music stop
/music next
/music prev
/music rescan
/music playlist create <歌单名>
/music playlist add "<歌单名>" <歌名>
/music playlist play <歌单名>
```

## 配置

- 客户端：`config/musicmod-client.properties`
- 服务端：`config/musicmod-server.properties`
- 歌曲：`config/musicmod/songs/`
- 歌单：`config/musicmod/playlists.json`

## 许可证

- 本模组自身代码：**MIT**，见 [LICENSE](LICENSE)；
- 内置音频库：LGPL-2.1 / Apache-2.0，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 `LICENSES/`；
- 本模组不附带任何音乐、封面或歌词文件；歌曲由服务器管理员自行提供。

Minecraft 是 Mojang Studios 的商标。本项目是非官方模组，与 Mojang 或 Forge 没有隶属或背书关系。
