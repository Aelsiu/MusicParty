# Ubuntu 完整部署包

适用于首次部署。包内包含前端和 Java 服务端、Java 21/FFmpeg 运行环境及网易云 API 的 Linux Docker 镜像，无需服务器安装 Node.js、Maven、Go，也无需部署时下载镜像。构建方法见源码仓库根目录的 `BUILD.md`。已部署用户使用 `build-update.ps1` 生成增量包。

## 服务器准备

- 64 位 Ubuntu 22.04 LTS 或以上，包架构必须匹配服务器：x86_64 使用 amd64，aarch64 使用 arm64。
- Docker Engine 24+、Docker Compose 插件 2.20+。按 [Docker 官方 Ubuntu 安装说明](https://docs.docker.com/engine/install/ubuntu/) 安装，包括 `docker-compose-plugin`。
- Bash、GNU tar、sha256sum、curl；缺少时执行 `sudo apt install bash tar coreutils curl`。
- 当前账户有 Docker 权限和部署目录读写权限，8848 端口可用。按网络需要开放该端口。建议预留至少 5 GB 磁盘空间。

## 上传与启动

从构建主机的 `dist/ubuntu/amd64/` 或 `dist/ubuntu/arm64/` 中选择与服务器架构对应的 `MusicParty-ubuntu-<版本号>-<提交号>.tar.gz` 并上传。应用版本只读取源码仓库根 `VERSION`（初始为 `1.3.9`），提交号为 `git rev-parse --short=7 HEAD` 的结果；调整发布版本只修改根 `VERSION` 并提交后重新构建。包名不含架构，架构只体现在构建输出子目录及包内 `PLATFORM`。

以下将 `1.3.9`、`abcdef0` 替换为实际产物名中的应用版本号和提交号：

```bash
mkdir -p /opt/music-party
app_version=1.3.9
commit=abcdef0
bundle="MusicParty-ubuntu-$app_version-$commit"
tar -xzf "$bundle.tar.gz" -C /opt/music-party
cd "/opt/music-party/$bundle"
cat APP_VERSION VERSION PLATFORM
cp config/application.properties.example config/application.properties
nano config/application.properties
```

必须设置 `app.rooms.root-key`（自己的 8–16 位 ASCII 可见字符密钥，无空格）和 `app.music-api.base-url`（浏览器可访问的服务地址，如 `http://服务器IP:8848`）。保留 `app.music-api.netease.base-url=http://netease-api:3000`。

密钥在文件中使用单行 `app.rooms.root-key=...` 格式，并遵循 Java properties 转义规则：实际密钥中的每个反斜杠写成 `\\`，长度按转义后的实际密钥计算。

```bash
bash deploy-docker.sh
```

脚本校验包内文件、加载两个镜像、启动服务并检查 `http://127.0.0.1:8848/api/config`。`APP_VERSION` 保存应用版本号，包内 `VERSION` 保留完整 Git 提交号，`PLATFORM` 保存镜像架构，均纳入 `SHA256SUMS` 校验清单。网页控制台 `window.__APP_VERSION__` 返回应用版本号，例如 `1.3.9`。检测到当前 Compose 项目已有容器时会拒绝重复执行。容器访问音乐平台仍需要网络。

此目录就是后续更新命令的 `--project-dir`。配置、许可保存在 `config/`，数据库和音乐缓存分别保存在 `music_party/data/`、`music_party/cached_media/`。不要在以后更新时替换或清空这些目录。

## 日常管理与排错

```bash
docker compose ps
docker compose logs --tail 100 music-party netease-api
docker compose stop
docker compose up -d --no-build --pull never
```

如果报 Docker 权限不足，使用具有 Docker 权限的账户，或执行 `sudo bash deploy-docker.sh`。若架构不匹配，重新构建相应 `-Platform` 的包。若 8848 被占用，先处理占用服务；自行修改端口后，还需调整脚本中的就绪检查以及增量更新脚本。SHA256 校验失败时重新上传原包。

启动检查超时可通过日志定位密钥配置、镜像或端口问题。修复后使用 `docker compose up -d --no-build --pull never` 重启；此时已有容器，首次部署脚本不会重复执行。升级方法见增量更新包内 README。
