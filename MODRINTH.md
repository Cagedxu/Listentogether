# Modrinth 发布清单

- **项目名**：一起听 (Listen Together)
- **版本**：`1.2.0`
- **游戏版本**：Minecraft `1.20.1`
- **加载器**：Forge `47+`
- **文件**：`build/libs/musicmod-1.0.0.jar`
- **项目许可证**：MIT（模组自身代码）
- **第三方组件**：LGPL-2.1 / Apache-2.0（详见 `THIRD_PARTY_NOTICES.md` 与 `LICENSES/`）
- **建议填写**：源码仓库、Issue Tracker、Discord/讨论区链接
- **可选**：自行设计或取得授权的项目图标

## 上传前检查

- [x] `mods.toml` 版本为 `1.0.0`，作者为 `deepseekharness和joycode`
- [x] jar 内包含 `LICENSE`、`THIRD_PARTY_NOTICES.md`、`META-INF/licenses/LGPL-2.1.txt`、`META-INF/licenses/Apache-2.0.txt`
- [x] 没有打包音乐、封面、歌词或 Mojang 资产
- [x] 上传功能仅 OP 可用，只发送玩家主动选择的文件
- [ ] 发布源码仓库（强烈建议；也便于满足 LGPL 的源码/重链接要求）
- [ ] 截图不要使用未授权的专辑封面或歌词；建议使用无封面/示例歌曲截图
- [ ] 在 Modrinth 描述中注明内置音频库及其许可证

## 仍建议处理的事项

当前构建把 LGPL 音频库的类文件合并进了模组 jar，并在 Notice 中提供了
上游源码与许可证。严格按 LGPL-2.1 解释，用户应能替换/重链接这些库；
如果 Modrinth 审核提出要求，建议改为将音频库作为独立的 Jar-in-Jar 发布，
或公开本模组源码与构建方式，方便用户重新打包。
