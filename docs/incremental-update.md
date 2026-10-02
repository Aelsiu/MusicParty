# Docker Compose 增量更新

适用于已经通过仓库自带 Compose 或 Ubuntu 完整部署包部署的用户。更新包基于根 `VERSION` 的应用版本号和构建时的 Git `HEAD` 生成，只传输包含前端的新 `app.jar`，在服务器**正在使用的主应用镜像**上添加一层，不重新下载或安装 FFmpeg、Java、NCM API，也不重建或重启 `netease-api` 容器。服务器不需要 Node.js、Maven、前端源码或访问软件仓库。

此流程要求新应用与已有运行环境兼容。更新若同时要求升级 Java、FFmpeg 或 NCM API，应另外安排对应组件升级。更早的单房间版本与当前多房间版本之间的数据兼容性，请先查阅该版本的迁移说明；保留文件不意味着任意旧数据库都能自动迁移。

## 1. 本机构建更新包

在仓库根目录运行。Windows 和 Ubuntu 均需 PowerShell 7.0+、Git 2.x、Node.js 22.12.0+、JDK 21、Maven 3.6.3+ 或仓库 Maven Wrapper、支持 gzip 的 `tar`。Wrapper 使用 Maven 3.9.12。工具的获取、环境检查与排错见仓库根目录的 `BUILD.md`。

```powershell
.\build-update.ps1 -CheckEnvironment
.\build-update.ps1
```

Ubuntu 的 Bash 中对应执行：

```bash
pwsh -NoProfile -File ./build-update.ps1
```

脚本每次根据锁文件运行 `npm ci`，再运行前后端测试，把前端打进 JAR。`-InstallDependencies` 保留为兼容参数，默认流程已经重新安装 npm 依赖。缓存完整且希望禁止 npm/Maven 依赖下载时用 `-Offline`。这些选项只涉及本机构建依赖，不涉及 FFmpeg、NCM API、Node 运行时或启动器。

脚本构建当前 `HEAD`，不会自动拉取远程提交。应用版本只读取源码仓库根 `VERSION`（初始为 `1.3.9`），不会随分支或 tag 自动替换；调整发布版本只修改该文件并提交。应用源码或依赖定义有未提交修改时，会拒绝给它标记为当前提交，请先提交准备发布的改动。构建开始时读取版本和提交，交付前再次检查两者未改变。输出名称为：

```text
dist/update/MusicParty-update-<version>-<commit>.tar.gz
```

`<version>` 是根 `VERSION` 中的应用版本，`<commit>` 是 `git rev-parse --short=7 HEAD` 的结果。包内顶层目录为 `MusicParty-update-<version>-<commit>/`，包含 `app.jar`、`Dockerfile.incremental`、`update-docker.sh`、`APP_VERSION`、`VERSION`、包内文件的 SHA-256 校验和与本说明。包内 `APP_VERSION` 保存应用版本，`VERSION` 保留完整 Git 提交号以兼容已有更新流程；两者都纳入校验清单。包内不含配置、许可、Cookie、数据库、音乐缓存或任何外部工具。

## 2. 上传并更新服务器

本机上传文件，替换示例中的用户名和服务器地址：

```powershell
$version = (Get-Content -LiteralPath .\VERSION -Raw).Trim()
$commit = (git rev-parse --short=7 HEAD).Trim()
scp ".\dist\update\MusicParty-update-$version-$commit.tar.gz" user@server:/tmp/
```

SSH 登录服务器后执行。下面的 `1.3.9`、`abcdef0` 为格式示例，替换为实际包名里的应用版本号和提交号；**把 `/opt/MusicParty` 替换成原有部署目录**。部署目录应包含正在使用的 `docker-compose.yml`、`config/` 和 `music_party/`：

```bash
app_version=1.3.9
commit=abcdef0
bundle="MusicParty-update-$app_version-$commit"
mkdir -p /tmp/music-party-update
tar -xzf "/tmp/$bundle.tar.gz" -C /tmp/music-party-update
bash "/tmp/music-party-update/$bundle/update-docker.sh" \
  --project-dir /opt/MusicParty
```

使用有 Docker 权限、能读写部署目录的账户；必要时在 `bash` 前加 `sudo`。服务器推荐 Docker Engine 24.0+、Docker Compose 插件 2.20+，并需要 Bash、`tar`、`sha256sum`、`curl`。脚本针对默认的 8848 端口和三个目录挂载；自定义部署应先调整脚本，挂载目录不匹配会拒绝执行。

更新脚本先校验 JAR，给现有容器的**实际镜像 ID**打保留标签，然后以此镜像作为基础，离线构建仅替换 `/app/app.jar` 的新镜像。构建完成后只停止主应用，备份 `config/` 和 `music_party/data/`（包括 SQLite 的 WAL/SHM），再替换主应用：

```bash
docker compose up -d --no-deps --no-build --pull never music-party
```

实际执行时脚本附加临时镜像配置，并校验启动后的镜像 ID、NCM 容器 ID 和 `/api/config`。通过检查后更新本地 `music-party-custom:local` 标签，后续正常 `docker compose up -d --no-build --pull never music-party` 仍使用新版本。由完整部署包安装的实例也使用此标签。

升级只会短暂中断主应用。房间、许可、Cookie、队列、历史和音乐缓存继续保留；当前歌曲的临时链接和播放进度遵循原有重启行为，重新进入后手动播放。刷新浏览器，在浏览器控制台输入 `window.__APP_VERSION__`，应返回更新包内 `APP_VERSION` 的应用版本号，例如 `1.3.9`。短提交号用于包名，完整提交号可查看包内 `VERSION`。不要使用更新包覆盖现有 `application.properties` 或清空数据库。

## 3. 失败或需要回退

备份保存在部署目录的 `music_party/update-backups/<时间戳>/`，脚本末尾会打印实际路径与回退命令。使用打印的路径执行：

```bash
bash "/tmp/music-party-update/$bundle/update-docker.sh" \
  --project-dir /opt/MusicParty \
  --rollback '/opt/MusicParty/music_party/update-backups/实际时间戳'
```

回退同样只停止主应用，先备份当前状态，再恢复旧镜像、升级前配置和数据库。升级后新增的数据会回到升级前状态，当前数据另有备份。失败后主应用已替换时请按打印的命令回退；不会自动覆盖数据库。旧镜像通过保留标签留在本机，回退前保留备份和这些镜像。

增量部署不需要服务器 `git pull`，不会修改服务器源码。运行 `docker compose up --build` 会根据服务器源码重新构建，因此只有服务器源码也更新后才使用它；通过完整部署包安装的目录没有构建源码，应继续使用 `--no-build --pull never` 启动。
