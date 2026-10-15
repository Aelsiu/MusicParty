# MirthParty 正式名称与兼容约定

正式名称从 2026-10-11 起统一为 **MirthParty**，无空格，保留此大小写。适用于网页、PWA、Windows 启动器、Android 应用和媒体通知、README、当前构建说明、Release 默认标题及新交付产物。

## 版本与构建命名

根目录 `VERSION` 是应用发布版本的唯一来源，当前为 `2.0.1`。更名不重置版本、Git 分支、提交历史或旧 tag。正常发布先提交源代码，再构建；版本调整只编辑 `VERSION`。正式发布 tag 沿用 `v<version>`。

| 对象 | 当前规则 |
|---|---|
| EXE / APK / 部署及增量包 | `MirthParty-<windows\|android\|ubuntu\|update>-<version>-<shortCommit>` 加对应扩展名 |
| Maven 项目 | `org.thornex:MirthParty`；JAR 为 `MirthParty-<version>-<shortCommit>.jar` |
| 运行显示版本 | `<version>-<shortCommit>`；例如 `2.0.1-abcdef0` |
| 包内 `APP_VERSION` | 运行显示版本 |
| 包内 `VERSION` | 完整 Git 提交号，兼容现有部署脚本 |
| Android `versionCode` | 沿用数值版本公式；同版本覆盖安装可显式提高 `-VersionCode`，继续使用原签名密钥 |
| 本地 Docker 镜像 | `mirthparty-custom:<version>-<shortCommit>-<arch>`、`mirthparty-netease:<version>-<shortCommit>-<arch>` |
| Compose 本地标签 | `mirthparty-custom:local`；部署、更新和回退同时维护旧别名 `music-party-custom:local` |
| 私有 npm 包 | `mirthparty-web`、`mirthparty-launcher-ui`、`mirthparty-launcher-root`；清单和锁文件同步 |
| Wails / Gradle 项目名 | `mirthparty-launcher` / `MirthParty` |

PowerShell 命名入口为 `scripts/build-metadata.ps1` 中的 `Get-MirthPartyBuildMetadata`、`Get-MirthPartyArtifactName` 和 `Assert-MirthPartyBuildMetadata`。旧 `MusicParty` 函数名保留为兼容别名，同样输出新名称。私有 npm 清单的 `0.0.0` 和 Maven 默认 `revision` 是开发占位值，正式构建使用根 `VERSION` 注入版本，不独立管理发布版本。

## 保留的兼容标识

以下标识属于已有安装、数据或调用契约，继续保留：Java 命名空间及启动日志 `org.thornex.musicparty` / `MusicPartyApplication`；Android `com.thornex.musicparty` 应用 ID、桥接及媒体动作；浏览器存储键、`musicparty:*` 事件与全局桥接；启动器 `.musicparty` 配置目录；Compose 服务 `music-party`、容器和网络名称、`music_party/` 数据挂载；源码目录 `music-party-web/`。

Web 将旧缺省背景字 `MUSIC PARTY` 和 `MIRTH PARTY` 显示为 `MirthParty`，不改写用户保存的配置。其他自定义背景字继续按原有大写规则展示。更名不会移动配置、缓存、数据库、许可、Cookie、签名密钥或原安装目录。

历史 Issue、PRD、SPEC、原型快照、验证记录、旧交付产物和上游归属链接保留当时名称，不重写历史。后续文件按新规则生成。

## 外部地址与本地知识库

本地文件夹改名不会自动改变 GitHub 仓库 URL、已有远端分支、Docker Hub 仓库或语雀知识库名称。现有 Git remote、源码链接、上游归属和 CI registry 地址继续使用实际地址；Docker 镜像的 OCI 展示标题已改为 MirthParty。若以后在对应平台更名，应先确认新地址可访问及发布权限，再同步 `git remote`、网页源码链接、两个 CI 工作流的 registry 说明，不能仅替换字符串后假定远端已迁移。

本地 codebase 图继续使用稳定 ID `musicparty-origin`，根目录为 MirthParty。知识 Git 与业务 Git 分开保存；基础设施迁移过程由本地知识页记录。

## 本轮验证（2026-10-11）

前端全量 461 项、独立生产构建、后端品牌 3 项、启动器 Go 配置测试、PowerShell 5.1/7 构建元数据与 Windows 打包夹具、品牌门禁、Ubuntu amd64/arm64 及 Android 三类产物夹具均通过。Docker 合成环境覆盖新旧镜像包部署、离线升级、失败保护和回退，知识导航/Git 隔离 33 项通过。

Git origin 的 `video-party` 分支可访问，远端仍为 `083d56f`；本地 HEAD 保持 `8617b68`，未推送本地历史。业务暂存区、根 `VERSION` 和原有未跟踪文件保持。本轮只更新源码与规则，未交付新的真实 EXE/APK、部署服务或触发 CI 发布。
