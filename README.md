# SPW Duet Lyrics（TTML / ELRC 对唱歌词）

[Salt Player for Windows / Linux](https://saltplayer.com/workshop/)（SPW，椒盐音乐桌面版）创意工坊 Mod：
让 SPW 正确显示 **TTML** 与 **增强 LRC（ELRC）** 歌词，包括逐字时间、`v1`/`v2` 对唱、背景人声与翻译。

- 不再把 TTML 的 `<?xml …>` / `<tt …>` 原始 XML 当成歌词显示
- 去掉 `v1:` / `v2:` / `[v1]` / `v1：` / `(v2)` / `【v2】` / `bg:` 等声部前缀
- 保留逐字（卡拉 OK）时间，转换为 SPW 原生支持的 [SPL](https://moriafly.com/standards/spl) 逐字格式
- 背景人声默认作为**独立的逐字歌词行**（SPW 不会在副行/翻译行上推进逐字进度）
- **左右对唱直接在 SPW 自带歌词界面中显示（1.1.0 起，不再需要 AMLL）**：`v1` 行靠左、`v2` 行靠右，背景人声跟随所属主行；逐字、翻译、动画仍由 SPW 渲染。需授予“类转换（class-transform）”权限，未授权时自动退回 SPW 的全局“歌词对齐”
- 可选联动 [AMLL into SPW](https://github.com/GaBoron/AMLL-into-SPW)：若已安装并启用，本插件仍会把对唱歌词（TTML）交给它渲染（此时由 AMLL 界面接管）

> 适用：SPW 1.19.0+（Workshop API 0.1.0-dev23），Windows 与 Linux。逐行对齐需要“类转换”权限，其余功能无需权限。

## 安装

1. 从 [Releases](https://github.com/Kifranei/spw-duet-lyrics/releases) 下载 `spw-duet-lyrics-<版本>.spmod`
2. SPW → **创意工坊 → 模组管理 → 导入模组**，选择该 `.spmod` 文件并确认
3. 在该 Mod 的操作菜单中选择 **启用**，并授予 **类转换** 权限（用于逐行对齐；拒绝时仅失去逐行对齐）
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

SPW 的歌词扩展点只能返回一段**歌词文本**（SPL），SPL 与 API 的 `LyricsLine` 都**没有声部或对齐字段**。
因此本插件在文本中只去掉 `v1:`/`v2:` 等声部前缀，不添加任何标记；对齐另由下面的 Hook 完成。

**逐行对齐（`align.per_line`，默认开，需要“类转换”权限，修改后重启 SPW 生效）**：SPW 的“歌词对齐”设置
（居中 / 靠左 / 靠右）作为一个样式对象传给每一行歌词的绘制函数 `LyricsLineItem`。本插件用 Workshop 的
`replaceComposable` 替换歌词列表中对 `LyricsLineItem` 的那一次调用：仍调用 SPW 自己的 `LyricsLineItem`，
仅当该行是本插件写入的对唱行（开始时间与文字都一致）时，把样式中的对齐换成靠左（主声部）或靠右（其他声部）。
其余行、非对唱歌词、合唱（group）声部都保持 SPW 的全局设置；任何不匹配或异常都按原参数调用。
SPW 的混淆类名随版本变化，插件启动时按 Compose 源码标记与调用形状在 SPW 自身的 jar 中查找目标，
找不到时不安装 Hook（歌词转换照常）。仅在 SPW 1.19.0 上验证过目标。诊断日志写在
`…\Salt Player for Windows\workshop\plugins\spw-duet-lyrics.log`。

**AMLL into SPW**（第三方 Mod，替换 SPW 原生歌词界面）：本插件通过它公开的进程内 JMX 协议
（`io.github.gaboron.amllspw:type=TtmlReceiver,version=1`）自动提交 TTML。启用 AMLL 时由它的界面接管，逐行对齐不再可见。

其他已知限制：
- 插件读不到 SPW 设置里的“自定义歌词目录”和在线歌词；那些歌词仍由 SPW 原生处理。
- SPL 中两句歌词不能同时间开始（后一句会变成翻译），同时开始的对唱行会被错开 1 ms。
- `v1000` 等合唱（group）声部保持 SPW 的全局对齐设置。
- SPW 会让一句歌词一直高亮到它的结束时间：SPW 会把“第一句正在播放的行”到“最后一句正在播放的行”之间的所有行都高亮，所以源文件里结束时间（包括最后一个字）晚于下一句开始的行，会被截断到下一句开始，哪怕只重叠几十毫秒（主句与它自己的背景人声仍可同时高亮，因为实际上是同时演唱的）；词与词之间的停顿写成 `<词尾><下一词开始>`，SPW 不再把上一个词一直填充到下一个词。
- 背景人声缺少的括号会被补全（如源文件中的 `(Yeah` → `(Yeah)`），已有括号不会重复添加。
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
| `align.per_line` | 开 | 在 SPW 自带歌词界面中按声部逐行左右对齐（需类转换权限，重启生效） |
| `amll.bridge` | 开 | 自动把对唱歌词交给 AMLL into SPW（左右对唱） |

## 构建

需要 JDK 25（Gradle Wrapper 9.8.0 自动下载）。

```sh
./gradlew test plugin        # Windows: .\gradlew.bat test plugin
# 产物：build/libs/spw-duet-lyrics-<版本>.spmod
```

源码结构：`core/`（与宿主无关的 TTML/ELRC 解析、SPL/TTML 输出，带单元测试）、`plugin/`（SPW 主类、扩展点、来源读取、AMLL 联动）、`align/`（逐行对齐 Hook）。

可选：`-PspwJars=<目录>` 指向含 SPW 1.19.0 应用 jar（安装目录 `app\ffmpeg-x64.dll`，可改名为 `.jar`）的文件夹，
测试会在真实 jar 上验证 Hook 目标；没有时该测试跳过。SPW 本体为专有软件，请勿提交到仓库。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。随包分发的 jaudiotagger 为 LGPL-2.1、ASM 为 BSD-3-Clause，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
本项目为社区项目，与 Salt Player 官方及 AMLL into SPW 无隶属关系。

---

### English summary

Salt Player for Windows/Linux workshop mod. Parses TTML (Apple Music style: agents, word spans, `x-bg`, translations)
and enhanced LRC with `v1:`/`v2:`/`bg:` voice markers, strips the markers, keeps word timing and hands SPW native SPL
through the documented `onBeforeLoadLyrics` extension point. Background vocals become separate word-timed lines.
Since 1.1.0, with the class-transform permission, it aligns duet lines per line inside SPW's OWN lyric view
(v1 left, v2 right, background vocals follow their main line) by replacing the per-line `LyricsLineItem` call with a
call to the same SPW function whose style carries a per-line `TextAlign`; everything else stays SPW's rendering.
Lines it did not write keep SPW's global alignment. It also feeds *AMLL into SPW* (GaBoron/AMLL-into-SPW) via its JMX protocol.
