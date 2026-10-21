# SPW Duet Lyrics（TTML / ELRC 对唱歌词）

[Salt Player for Windows / Linux](https://saltplayer.com/workshop/)（SPW，椒盐音乐桌面版）创意工坊 Mod：
让 SPW 正确显示 **TTML** 与 **增强 LRC（ELRC）** 歌词，包括逐字时间、`v1`/`v2` 对唱、背景人声与翻译。

- 不再把 TTML 的 `<?xml …>` / `<tt …>` 原始 XML 当成歌词显示
- 去掉 `v1:` / `v2:` / `[v1]` / `v1：` / `(v2)` / `【v2】` / `bg:` 等声部前缀
- 保留逐字（卡拉 OK）时间，转换为 SPW 原生支持的 [SPL](https://moriafly.com/standards/spl) 逐字格式
- 背景人声默认作为**独立的逐字歌词行**（SPW 不会在副行/翻译行上推进逐字进度）
- **左右对唱需要 [AMLL into SPW](https://github.com/GaBoron/AMLL-into-SPW)**：SPW 原生界面按原样显示歌词、不支持左右对齐；安装并启用 AMLL into SPW 后，本插件会自动把对唱歌词（TTML）交给它渲染

> 适用：SPW 1.19.0+（Workshop API 0.1.0-dev23），Windows 与 Linux。无需任何权限。

## 安装

1. 从 [Releases](https://github.com/Kifranei/spw-duet-lyrics/releases) 下载 `spw-duet-lyrics-<版本>.spmod`
2. SPW → **创意工坊 → 模组管理 → 导入模组**，选择该 `.spmod` 文件并确认
3. 在该 Mod 的操作菜单中选择 **启用**（本 Mod 不申请权限）
4. 切歌或重新加载歌词后生效；设置在操作菜单 **配置** 中

## 歌词来源

插件通过 Workshop API 的 `onBeforeLoadLyrics` 在 SPW 默认逻辑之前提供歌词，按以下顺序读取：

1. 歌曲同名 `.ttml`
2. 歌曲同名 `.elrc`
3. 音频内嵌歌词（ID3 `USLT` / `TXXX:LYRICS`、FLAC/Vorbis `LYRICS`/`UNSYNCEDLYRICS`、MP4 `©lyr` 等）
4. 歌曲同名 `.lrc`

`.ttml` / `.elrc` 是专门存放富格式歌词的文件，SPW 自己不读取，所以总是最先；内嵌歌词与同名 `.lrc` 的先后可在设置中对调。

取到的第一份歌词：

| 内容 | 处理 |
| --- | --- |
| TTML（有无 `<?xml?>` 均可） | 解析并转换为 SPL |
| LRC 含 `v1:`/`v2:`/`bg:` 等标记 | 解析、去前缀、转换为 SPL |
| 普通 LRC / 无标记的逐字 LRC | 交还 SPW 原生解析（可在设置中改为全部接管） |

## 支持的格式

**TTML（Apple Music / AMLL 风格）**
`<head><metadata><ttm:agent xml:id="v1" type="person">`、`<p begin end ttm:agent itunes:key>`、逐字 `<span begin end>`、
背景人声 `<span ttm:role="x-bg">`、行内翻译/音译 `x-translation` / `x-roman`、
`<iTunesMetadata><translations>`/`<transliterations>`（按 `itunes:key` 关联，含背景人声翻译）、
时钟时间（`1:02.345`、`0:01:02.345`）与偏移时间（`12.3s`、`500ms`）。禁止 DTD/外部实体。

**ELRC / 增强 LRC**
`[mm:ss.xx]`、`[mm:ss:xx]`、重复时间戳 `[t1][t2]`、逐字 `<mm:ss.xx>` 与 SPL 行内 `[mm:ss.xx]`、
声部 `v1:` `V2 :` `v1：` `[v1]` `(v1)` `【v1】`（也可写在首个逐字标签之后），背景人声 `bg:` 行与 Lyricify 风格 `[bg: <t>…]`，
同时间戳翻译行、紧随的无时间戳翻译行、`[offset:±ms]`、空时间戳行作为行结束。
未标记的行沿用上一条标记行的声部。

## 对唱显示与限制（重要）

SPW 的歌词扩展点只能返回一段**歌词文本**，SPW 按 SPL 解析后**原样显示**；SPL 与 API 的 `LyricsLine`
都**没有声部或对齐字段**，行首缩进等文本技巧在 SPW 中也不起作用。因此本插件在原生界面中只去掉
`v1:`/`v2:` 等声部前缀，不添加任何标记。

**真正的左右对唱需要安装 [AMLL into SPW](https://github.com/GaBoron/AMLL-into-SPW)**（第三方 Mod，替换 SPW 原生歌词界面）。
本插件会通过它公开的进程内 JMX 协议（`io.github.gaboron.amllspw:type=TtmlReceiver,version=1`）自动提交 TTML：
TTML 来源直接提交原文；ELRC 来源按原始逐字时间与声部生成 TTML 后提交。未安装 AMLL 时没有任何影响。

其他已知限制：
- 插件读不到 SPW 设置里的“自定义歌词目录”和在线歌词；那些歌词仍由 SPW 原生处理。
- SPL 中两句歌词不能同时间开始（后一句会变成翻译），同时开始的对唱行会被错开 1 ms。
- SPL 无“居中”，`v1000` 等合唱（group）声部按左侧处理。
- 背景人声默认是独立的逐字歌词行（开始时间与主句相同时错开 1 ms）；也可改为主句下方的括号副行（SPW 不在副行上推进逐字进度）或隐藏。

## 设置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | 开 | 总开关 |
| `source.order` | `EMBEDDED_FIRST` | 内嵌歌词与同名 `.lrc` 的先后（`.ttml`/`.elrc` 总是最先） |
| `bg.style` | `SEPARATE` | `SEPARATE`（独立逐字行）/ `SUBLINE`（括号副行）/ `DROP` |
| `translation.enabled` | 开 | 显示翻译 |
| `romanization.enabled` | 关 | 显示音译 |
| `elrc.normalize_all` | 关 | 无标记的逐字 LRC 也由插件转换 |
| `amll.bridge` | 开 | 自动把对唱歌词交给 AMLL into SPW（左右对唱） |

## 构建

需要 JDK 25（Gradle Wrapper 9.8.0 自动下载）。

```sh
./gradlew test plugin        # Windows: .\gradlew.bat test plugin
# 产物：build/libs/spw-duet-lyrics-<版本>.spmod
```

源码结构：`core/`（与宿主无关的 TTML/ELRC 解析、SPL/TTML 输出，带单元测试）、`plugin/`（SPW 主类、扩展点、来源读取、AMLL 联动）。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。随包分发的 jaudiotagger 为 LGPL-2.1，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
本项目为社区项目，与 Salt Player 官方及 AMLL into SPW 无隶属关系。

---

### English summary

Salt Player for Windows/Linux workshop mod. Parses TTML (Apple Music style: agents, word spans, `x-bg`, translations)
and enhanced LRC with `v1:`/`v2:`/`bg:` voice markers, strips the markers, keeps word timing and hands SPW native SPL
through the documented `onBeforeLoadLyrics` extension point. Background vocals become separate word-timed lines.
SPW renders lyric text as-is and its API has no alignment field, so in the native view the markers are simply stripped
(no marker of any kind is added). Real left/right duet layout requires the community mod *AMLL into SPW*
(GaBoron/AMLL-into-SPW), which this mod feeds automatically via its documented JMX protocol. No permissions required.
