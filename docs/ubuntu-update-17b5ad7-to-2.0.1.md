# Ubuntu 默认 Compose 增量更新：17b5ad7 → 2.0.1

适用现状：仓库自带 Docker Compose、对外端口 **8848**，现部署提交 `17b5ad701e3b3e8de7c8642a15dced4246380e8c`。本包应用源码来自新提交 `8617b686c3842e00a159a67ea175a7fa5e9a1912`，安装后运行版本为 **`2.0.1-8617b68`**，包含匹配前端。不要用之前的 Windows JAR 或仅替换旧前端。

包名：`MusicParty-update-2.0.1-8617b68.tar.gz`。`VERSION` 记录完整应用提交；`APP_VERSION` 记录 `2.0.1-8617b68`，`RELEASE_VERSION` 为 `2.0.1`；`SOURCE.json` 和 `SOURCE-INPUTS.json` 提供构建与源文件摘要。`apply-update.sh` 是专用部署伴随入口，包内已绑定此提交；应用源码与部署入口的版本身份分开记录。

## 可行性及数据影响

**不需要在服务器重新下载 FFmpeg 或 ncmapi。** 更新在现有主应用的实际本地镜像上新增一层 JAR，不重装 Java/FFmpeg；`netease-api` 使用原运行容器，不拉取、不重建、不重启。预检要求它已经运行。服务器只需 Bash、Docker/Compose、默认 `docker` driver 的 Buildx，以及已有 `tar`、`sha256sum`、`curl`、`flock`。缺少条件会报错，不会自动安装或下载依赖。

使用默认集成 Docker builder、已存在的本地基础镜像、`--pull=false` 和无安装命令的 `Dockerfile.incremental` 实现镜像复用；`--network=none` 单独并不能保证禁止镜像仓库访问。[Docker builder 文档](https://docs.docker.com/build/builders/drivers/docker/)、[Docker build 文档](https://docs.docker.com/reference/cli/docker/buildx/build/)。替换时仅调用主应用的 `--no-deps --no-build --pull never`，不处理 NCM 服务。[Compose up 文档](https://docs.docker.com/reference/cli/docker/compose/up/)。

- SQLite 表结构不变，无需删库或手工迁移。原房间、Cookie、许可、待播音乐、历史、聊天和成员绑定沿用。Cookie 的真实有效性由平台决定。
- 必须保留原 `config/`（包括许可文件、ROOT key）和 `music_party/data/`。缺少旧许可文件可能触发两版已有的房间清理规则；脚本检查三个挂载路径以避免换到空目录。
- ROOT 系统配置首次启动会补充视频上限：120 分钟、分辨率 **不限制**。房间快照正常保存时增加视频字段，不清除旧音乐数据。
- 主应用停止期间网页/WebSocket/解析/扫码任务中断。旧版没保存当前播放位置；正在播放且尚未入历史的歌曲可能需重新点播。升级后恢复暂停，需手动播放。
- 管理会话重启后须重新验证许可；刷新所有客户端页面，载入协议 3 的新前端。若有反向代理/CDN 缓存，也应清理旧首页缓存。
- 原缓存 `music_party/cached_media/` 保持原位，不复制、不清理。原服务器源码与 Compose 文件不改写；本地 `music-party-custom:local` 标签会指向更新后的镜像。

详见同包 `ASSESSMENT.md`。本包不需要用户已允许的破坏性迁移。

## 使用步骤

以下 `/opt/MusicParty` 是示例，必须替换为**当前服务的原部署目录**，其中已有 `docker-compose.yml` 和三个原挂载目录。更新包应解压到独立目录，不能覆盖原部署目录。

### 1. 上传并校验

把 `.tar.gz` 和同名 `.tar.gz.sha256` 一起上传至服务器，例如 `/tmp/musicparty-update/`。在该目录执行：

```bash
cd /tmp/musicparty-update
sha256sum --check MusicParty-update-2.0.1-8617b68.tar.gz.sha256
tar -xzf MusicParty-update-2.0.1-8617b68.tar.gz
cd MusicParty-update-2.0.1-8617b68
sha256sum --check SHA256SUMS
```

不要修改包内文件；所有文件均在校验清单中。校验失败时重新上传正确文件。

### 2. 预检

```bash
sudo bash ./apply-update.sh --project-dir /opt/MusicParty --check-only
```

预检核验 Linux Engine、默认本地 builder、原 NCM 容器、三个原挂载及主应用本地镜像；仅运行一个无网络、无数据挂载的临时容器检查旧镜像中的 Java 21/FFmpeg，不启动应用。预检会创建或复用原部署目录的 `.musicparty-update.lock` 锁文件，不停主应用、不改配置/数据库。通过预检不等于真实媒体播放已验收。

如果 NCM 容器未运行、原镜像已删除、不是默认挂载，或 builder 不是本地 `docker` driver，请先恢复原部署条件。本入口会拒绝继续，避免为了更新拉取依赖。

### 3. 执行更新

建议选空闲时段；如需继续当前歌曲，先记录歌曲公开 ID，并让正在提交的操作完成。

```bash
sudo bash ./apply-update.sh --project-dir /opt/MusicParty
```

脚本先构建应用镜像层，成功后仅正常停止主应用（最长 30 秒），备份 `config/` 和 `music_party/data/`，包括存在的 SQLite WAL/SHM，再创建主应用容器。它核验容器使用预期镜像且 NCM 容器 ID 未变，并等待 `http://127.0.0.1:8848/api/config` 最多 120 秒。实际停机时间主要是停止、数据备份与启动时间，不能给固定时长。

输出中的 **`Backup: ...`** 是本次原状态备份路径，请保存。默认位置：

```text
原部署目录/music_party/update-backups/UTC时间-进程号/
```

备份中 `state.tar.gz` 含配置与数据库，`image-id` 记录原应用镜像；缓存不在备份中。不要删除旧本地镜像或这些备份。Docker 构建会增加一个应用层，需要为 JAR、原状态备份和镜像层预留空间。

### 4. 验收

打开原 8848 地址并强制刷新。界面版本应为 **`2.0.1-8617b68`**；可在浏览器开发者工具运行 `window.__APP_VERSION__` 确认。重新验证 ROOT/Owner 许可，检查原房间数量、设置、待播与历史；再手动启动一首原音乐，并测试一起看视频和 Cookie 登录入口。两个平台真实账号状态、实际视频解码及网络能力需要在服务器验收。

`Update complete` 只表示镜像与 HTTP 就绪检查通过，不能代替数据与播放验收。服务器源码仍是旧提交，执行 `git rev-parse HEAD` 不代表运行应用版本；应看新界面及包内提交记录。本地默认镜像标签已同步，后续普通 `docker compose up -d` 会使用该镜像；重新 `--build` 会按原服务器旧源码构建回旧应用。

## 回滚

必须使用**该部署自身生成、未经手工修改的完整备份**，不要使用来源不明或手工编造的回滚 TAR。旧版本保存新版数据库会丢新增视频字段，因此回滚会一起恢复升级前配置/数据库和旧镜像。

把下列最后的路径换成此次更新输出的准确 `Backup` 路径：

```bash
sudo bash ./apply-update.sh \
  --project-dir /opt/MusicParty \
  --rollback /opt/MusicParty/music_party/update-backups/实际UTC时间-进程号
```

**恢复旧备份会放弃升级后新增的队列、聊天、Cookie 和设置变更。** 回滚操作也会先停止主应用并把回滚前当前状态另存一份备份，便于人工恢复。原缓存仍保留；NCM 仍使用原容器。可靠回滚依赖原旧镜像仍在本机，不能提前执行镜像清理。

底层冻结更新器可能打印 `update-docker.sh` 命令；请使用上面的 **`apply-update.sh`** 入口和相同备份路径，以保留锁及预检。

如应用替换后启动检查失败，脚本保留新状态、打印备份路径，**不会自动覆盖数据库回滚**；查看主应用错误后，按上述命令回滚。若在停服后、替换前失败，脚本会尝试重新启动原主应用。回滚目录恢复中断时，会尝试归还原目录；无法归还时按输出恢复目录后再启动。

## 本次验证边界

详见同包 `VALIDATION.md`。源文件 Git 摘要、前端/后端测试、JAR 与静态资源一致性、合成旧 SQLite、模拟 Docker 更新/回滚均已核验。构建机器为 Windows，本机 Docker Engine 未运行；没有启动 Ubuntu Docker 实例，也没有接触服务器生产数据或账号。因此实际 Ubuntu 启动、Linux 文件锁竞争及真实媒体/扫码仍需要上述服务器验收。
