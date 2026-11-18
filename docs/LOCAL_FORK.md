# 本地二改分支说明

本工程是 [daxiaamu/Guise_Reborn](https://github.com/daxiaamu/Guise_Reborn) 的本地分叉，
在保留上游功能的前提下增加了自用改动。

## 分支结构

| 分支 | 用途 |
|---|---|
| `main` | 跟踪上游，不要在这里提交 |
| `local` | **日常使用/构建的分支**，本地二改都在这里 |
| `backup-local` | rebase 前的自动备份，出问题可回退 |

## 本地改动清单（相对上游 v2.1.4）

| 提交 | 内容 |
|---|---|
| `local: 独立包名` | `applicationId` 改为 `com.houvven.guise.coordfix`，可与官方版共存 |
| `local: 坐标系统转换` | 新增输入/输出坐标系（WGS-84 / GCJ-02 / BD-09），解决部分 App 自行转换导致落点偏移 |
| `local: 版本号提到 900033` | 阻断内置更新器判定上游为"可更新"，避免二改被覆盖 |

改动面很小：10 个文件、约 +195/-29 行，集中在
`ModuleConfig` / `ModuleConfigState` / `ModuleConfigManager` / `ConfigEditor` / `LocationHook`。

## 为什么需要坐标系统转换

部分 App（如微信）拿到注入坐标后会**自己**再做一次 WGS-84 → GCJ-02 转换。
如果直接注入目标 GCJ 坐标，最终落点会偏移约 500~600 米。

正确做法是注入 `gcj2wgs(目标GCJ坐标)`，让 App 自己转换后正好落回目标点。
界面上的「输入坐标系 / 输出坐标系」就是干这个的：
填 GCJ-02 目标值，输出选 WGS-84，模块会自动反算。

## 同步上游更新

```powershell
# 1. 先看有哪些新版本（不改任何东西）
.\tools\sync-upstream.ps1

# 2. 实际同步（自动备份到 backup-local）
.\tools\sync-upstream.ps1 -Apply

# 3. 若有冲突，解决后继续
git add -A
git rebase --continue

# 4. 重新构建
.\gradlew.bat assembleDebug
```

已知冲突点（上游与本地在同一位置加字段）：

- `ModuleConfig.kt` —— 上游加 `randomOffsetMeters`，本地加两个坐标系字段 → **两边都留**
- `ModuleConfigState.kt` —— `nonBooleanStates` 列表 → **两边都留**
- `ConfigEditor.kt` —— import 段 → **两边都留**

## 关于内置更新器

上游的更新器会比较 `versionCode` 与 GitHub releases，然后下载并安装上游 APK。
本工程把 `versionCode` 设为 `900033`，使上游任何版本都不会被判定为更新，
从而不会误装上游 APK（那会装成另一个包名，并丢掉二改功能）。

要跟进上游请走上面的 `sync-upstream.ps1`，不要用 App 内的检查更新。

## 构建注意

- 工程路径含中文，`gradle.properties` 里需要有 `android.overridePathCheck=true`
- `version.properties` 的 `prerelease` 建议保持 `false`，避免去查预发布清单
