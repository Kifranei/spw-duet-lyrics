# 发布与工坊收录（调研结论，2026-10-20）

来源：<https://saltplayer.com/workshop/publishing.html>、<https://saltplayer.com/workshop/usage.html>、
<https://github.com/Moriafly/spw-workshop-api>。

- **没有官方插件注册仓库，也没有“SPW 组织”**。GitHub 上不存在 `SPW` / Salt Player 官方组织；官方仓库都在个人账号
  `Moriafly` 下（`Moriafly/SPW`、`Moriafly/spw-workshop-api`）。迁移到某个组织**不会**让插件出现在播放器里。
- **GitHub 渠道**：把 `.spmod` 发布到自己仓库的 GitHub Releases，并给仓库加 topic **`salt-player-plugins`**。
  官网“GitHub 插件列表”链接即 <https://github.com/topics/salt-player-plugins>；文档称加 topic 是为了“方便用户从播放器或本站找到插件”。
  用户仍需手动下载并在“模组管理 → 导入模组”导入（`.spmod`/`.jar`/`.zip`）。
- **播放器内可订阅的列表 = Steam 创意工坊**（App 3009140）。仅 Steam 渠道可用：Steam 启动项加 `--workshop-publisher`
  → 创意工坊 → 创意工坊创作中心 → 发布新 Mod，上传 `.spmod` + 小于 1 MB 的 PNG/JPG/GIF 封面；新条目默认私有，需在 Steam 页面改可见性。
  发布器只接受 `.spmod`。
- **清单要求**：由 Gradle 插件 `com.xuncorp.spw.workshop` 写入 `classes/META-INF/MANIFEST.MF`：
  必填 `Plugin-Class`、`Plugin-Id`、`Plugin-Version`；可选 `Plugin-Provider`、`Plugin-Name`、`Plugin-Description`、
  `Plugin-Open-Source-Url`、`Plugin-Has-Config`、`Plugin-Permissions`。更新时保持 `Plugin-Id` 不变并递增版本。
  官方鼓励开源、不混淆。
- **仓库命名**：无要求（社区多用 `SPW-xxx` / `spw-xxx`）。

## 本仓库发布清单（尚未执行）

1. 在 GitHub 创建 `Kifranei/spw-duet-lyrics`（与 `PluginOpenSourceUrl` 一致；改名需同步 `build.gradle.kts`）
2. 添加 topic：`salt-player-plugins`（可再加 `salt-player`、`spw`、`ttml`、`lyrics`）
3. 用 `gh release create` 发布并手动附加 `.spmod` 与 `.sha256`（CI 只构建测试、上传 Actions artifact，不改动 Release 资产，避免重复）
4. （可选，Steam 渠道）用创意工坊创作中心上传同一 `.spmod`
