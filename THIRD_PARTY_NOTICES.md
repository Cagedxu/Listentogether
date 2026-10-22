# Third-Party Notices

「一起听」(Listen Together) 的本体代码以 **MIT License** 发布（见根目录 `LICENSE`）。

构建产物中合并了以下开源音频解码库。它们仍然是各自作者的版权作品，并继续适用各自的许可证；本模组未修改其源代码。

| Component | Version | License | Upstream source |
| --- | --- | --- | --- |
| Tritonus Share | 0.3.7.4 | LGPL-2.1 | https://github.com/umjammer/tritonus |
| MP3SPI | 1.9.5.4 | LGPL-2.1 | https://github.com/umjammer/mp3spi |
| VorbisSPI | 1.0.3.3 | LGPL-2.1 | https://github.com/umjammer/vorbisspi |
| JLayer | 1.0.1.4 | LGPL-2.1 | https://github.com/umjammer/jlayer |
| JOrbis | 0.0.17.4 | LGPL-2.1 | https://github.com/umjammer/jorbis |
| jFLAC Codec | 1.5.2 | LGPL-2.1 | https://github.com/umjammer/jflac |
| JAAD (Tianscar fork) | 0.9.4 | Apache-2.0 (as declared by upstream POM) | https://github.com/Tianscar/jaadec |

LGPL-2.1 与 Apache-2.0 的完整许可证文本位于：

- `LICENSES/LGPL-2.1.txt`
- `LICENSES/Apache-2.0.txt`

## LGPL source availability

The LGPL-2.1 components are redistributed unmodified. Their complete source
code, including the exact released versions, is available from the upstream
repositories listed above. No proprietary code has been added to these
libraries, and their copyright notices remain with their respective authors.

由于构建时把解码库类文件合并进同一个模组 jar（用于避免 Java Sound SPI 在
Forge Jar-in-Jar 下的 split-package 冲突），如果你需要修改或替换这些库，请使用
上述上游源码重新构建对应库后重新打包本模组。

## Not bundled

- 没有附带任何音乐、封面或歌词文件；歌曲由服务器管理员自行提供。
- 没有附带 Minecraft / Mojang 的材质、音效或其他资产。
- 没有附带 Forge 或其他非音频依赖；它们由运行环境提供。

## Trademarks

Minecraft 是 Mojang Studios 的商标。本项目是非官方模组，与 Mojang 或 Forge
没有隶属或背书关系。
