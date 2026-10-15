# Ubuntu 增量升级评估：17b5ad7 → 2.0.1-8617b68

评估日期：2026-10-10。目标是复用 Ubuntu 现有 FFmpeg 与 ncmapi，仅升级 MusicParty 应用及其匹配前端。本报告基于准确源码比较和合成 SQLite，不读取运行数据库、配置凭据或用户媒体。功能比较与合成验证首先完成于 `898c754`；随后用户要求统一版本 `2.0.1`，新目标提交只增加版本与构建显示合同，应用运行源码和数据库合同未改变。

## 结论

从房间持久化与配置合同看，增量升级可行，**不需要删库、清房间或执行 SQL 数据迁移**。两个提交的 SQLite 建表与补列逻辑一致；旧房间、房间 Cookie、待播音乐、历史、许可和成员绑定均有兼容读取路径，独立合成 SQLite 验证 4/4 通过。

应用升级仍需要停服重启，前后端必须成套更新。当前播放场景和进度不是持久化数据；重启后房间恢复为暂停、无当前播放场景，不能承诺续播到原位置。旧版本再次保存新数据库会丢失新增的视频队列/历史和模式字段，因此可靠回滚需要升级前的一致数据备份。

FFmpeg 路径与网易 API 地址继续取部署配置，两版本 `RoomRuntimeConfig` 的这一逻辑没有变化。只要增量方案保留现有运行时、ncmapi 服务和这些配置，就不需要重新下载 FFmpeg 或 ncmapi；镜像/脚本如何落实这一点须由增量包另行验证。用户已确认当前使用原仓库默认 Compose 的 8848 对外端口，应保留现有映射。

## 精确比较基线

| 项目 | 提交 |
|---|---|
| 现 Ubuntu 服务基线 | `17b5ad701e3b3e8de7c8642a15dced4246380e8c` — release 1.4.5 |
| 功能验证检查点 | `898c7548721f17cf0c2c457eb3fc92ba0feae70e` — Mirth Party controls/playback/cookie login |
| 最终应用构建提交 | `8617b686c3842e00a159a67ea175a7fa5e9a1912` — release 2.0.1，运行显示 `2.0.1-8617b68` |

以 `git show` / `git diff 17b5ad7 898c754` 核对功能检查点，再核对最终提交相对检查点只含版本与构建合同调整，而不是泛指 master 或当前未提交草稿。`src/main/java` 工作区无差异。此次新增迁移测试和本报告是伴随验证资料，不是对目标应用运行源码的修改。

重点核对 `RoomRepository`、`QueuePersistenceService`、`SettingsSnapshot`、`SystemConfigSnapshot`、`SystemConfigService`、`RoomLifecycleService`、`RoomRuntimeConfig`、`RoomAccessService` 和配置控制器；另核对 `MusicQueueManager` / `MusicPlayerService` 的恢复与计时链。

## 数据与配置兼容

| 持久化内容 | 变化与升级行为 | 迁移要求 |
|---|---|---|
| SQLite 表/列 | 两提交均为 `rooms`、`pairing_cooldown`、`profiles`、`creations`、`admissions`、`system_config`、`pairing_clock`。`rooms` 的配对/分享/公开/版本四列和 `admissions` 的公开/版本两列均已在旧版补列逻辑中。新版仅新增 `saveChecked` 的更新行数/权限 guard，没有新 DDL。 | 无 SQL 迁移；继续使用原数据库路径。 |
| 房间元数据 | roomId、名称、owner_id、pair_code、pair_epoch、auto_open、pairing_open、share_enabled、public_room、access_version 均沿原结构读取。 | 不需重建房间。配对码仍会按原时钟到期轮换。 |
| `rooms.config` | 原 `AppProperties` JSON 仍可读；房间 API 地址与 FFmpeg 路径继续由部署默认配置覆盖。新增视频上限不以房间配置为权威，保存时排除。 | 保留部署配置、数据库、工作目录/挂载路径。 |
| 房间 Cookie | 网易与 B站 Cookie 仍存 `rooms.config` 的对应 `cookie` 字段；不存在改格式、统一账号、强制清空或自动登录迁移。新扫码持久化只增加严格候选保存。新 Cookie 状态检查只读且不删除凭据。 | 配置值可保留；是否仍有效取决于平台，合成兼容测试不证明真实账号有效。 |
| 音乐队列、历史、聊天 | 旧 payload 的 `queue`、`history`、`chatHistory`、`settings` 仍读取；`Music`、`MusicQueueItem` 和 `ChatMessage` 在两提交间未改结构。保留队列 ID、成员、优先级、歌曲元数据。历史仍按当前 ROOT 容量裁剪。 | 不需转换旧列表；不能把“保留待播队列”理解为保存当前播放场景。 |
| 新 payload 字段 | 新保存写 `snapshotVersion:2`、`videoQueue`、`videoHistory`；settings 新增 `mediaMode`、`videoPlayer`，player 新增可空 `playbackRate`。旧数据缺字段时：版本视为 0，视频列表为空，模式最终归 MUSIC；可空设置不强制覆盖默认。 | 兼容读取后，首次正常保存自然升级 JSON。 |
| 共享投票设置 | 新版 player 快照只拥有播放模式/锁等模式设置；共享投票以 room config 为权威。旧版本快照仅在 config 对应投票字段缺失且没有外部配置权威时补缺。 | 正常一致数据无需处理；若旧 config 与快照人为冲突，新版选择 config，可能与旧恢复行为不同。 |
| ROOT `system_config` | 原九项全局值继续读取。旧 JSON 缺视频字段时补 `videoMaxDurationMinutes=120`、`videoMaxResolution=UNLIMITED`、`videoPolicyRevision=0`；启动重写扩展 JSON。若原 global 行不存在，则从部署默认值种子生成。运行期视频 revision 启动重置 0。 | 自动、非破坏性 JSON 扩展，不需人工 SQL。既有全局行的缺视频字段使用合同默认，不由房间上限或旧快照覆盖。 |
| 许可/成员档案 | `licenses.json` 的许可 ID/key/note 和 profiles 的 name/bindings 格式未变。管理会话仍只在内存，重启需重验许可；有效 admission 可从 SQLite 恢复，但仍检查期限、epoch、公开版本。 | 原许可文件和 ROOT key 必须保留；浏览器可能需要重新输入许可或入房。 |

`RoomRuntimeConfig`、`RoomConfigFileService`、`UserService`、`MultiRoomProperties` 与 `SystemConfigController` 在这两个提交之间未发生源码变化。ROOT 独占全局参数、Owner 仅管理所属房间的边界保持；新视频上限属于 ROOT 全局参数，Owner 不应尝试写到房间 config 中。

## 播放恢复与升级中断

旧版 payload 没有 `nowPlaying`、当前音乐 URL、positionAnchor/timestampAnchor 或运行期播放标识。旧音乐播放器选择歌曲时已将其从待播队列取出；普通歌曲在结束时才入历史。因此当前未播完的普通歌曲可能既不在待播快照也不在历史，升级不能从旧快照自动恢复它。如果需要继续这一首，停服前记录可重新点播的公开歌曲标识；不需要导出播放 URL 或账号凭据。

新版 `RoomPlaybackCoordinator.start` 在 mailbox 内确保一次恢复，使用缺失模式的 MUSIC 回退，并激活暂停状态。`QueuePersistenceService.loadData` 保留旧队列与历史后执行 `pauseForEmptyRoom`。合成测试中的真实 MusicPlayer 恢复为暂停、`nowPlaying=null`、positionAnchor 为 0；需要用户再开始播放，从可解析的队列项重新取源。

新版生命周期关闭会先 quiesce，取消在途工作，然后进行最终严格保存，再销毁 room scope；删除房间路径不保存。这减少停止过程中迟到工作污染，但升级应正常停止服务，避免强杀错过最后保存。旧版定时快照与正常关闭仍有它原先的保存窗口；临近停服的未保存变更不能由升级补回。

新 STOMP CONNECT 要求 `playback-protocol=3`，新版播放管理接口也要求协议与 epoch 合同。旧缓存页面可能收到 `CLIENT_UPGRADE_REQUIRED`，因此必须使用目标提交对应静态资源并刷新页面，不能只替换后端 JAR 后继续旧页面。所有 WebSocket、直播流、下载/解析任务及扫码任务在服务重启时终止；它们不是 SQLite 迁移对象。

## 必须防止的升级/回滚风险

- **路径或挂载改变造成看起来“丢数据”。** 相对 SQLite 默认路径是 `data/multi-rooms.sqlite`，普通许可默认 `config/licenses.json`。如果增量容器/服务换了工作目录或挂载，新进程可能创建空 DB/许可文件。保留原配置与数据目录，不能把程序目录整体替换成空发行目录。
- **许可文件缺失会删除 Owner 房间。** 两版本已有同一恢复规则：初始化时，不属于 ROOT 且找不到对应许可 ID 的房间会被删除。误用空/不同 licenses.json 的风险来自部署文件不一致，不是本次新增迁移。不能只备份 SQLite 而遗失许可文件。
- **已保存的非法全局参数现在启动失败。** 新版在 initialize 中先验证已读取的 global 值，再保存。例如旧 JSON `maxQueueSize=0` 会拒绝启动，保留原数据而不是抹库。旧正常管理入口本来也校验参数；这一风险主要针对手改、损坏或非标准历史数据。
- **回滚只有旧二进制不保证保留新功能数据。** 旧 Spring/Jackson 默认读取可忽略新增字段，但旧保存只输出旧四个 payload 字段；第一次旧保存便丢弃 videoQueue/videoHistory/mediaMode 等。旧全局保存也会移除新视频政策字段。严格开启 unknown-property 失败的自定义旧 Mapper 更可能直接拒绝新版 JSON。
- **网络/媒体能力须在 Ubuntu 实测。** 现有 Cookie 能读不等于平台仍有效；旧缓存文件、FFmpeg 编解码能力、ncmapi 可达性与新 B站实际取源不由纯持久化测试证明。无需重新下载依赖，但升级后应验证已有依赖可工作。

建议在正常停服后，对原 SQLite 及相关 `-wal`/`-shm` 状态做一致备份，同时保留原 config、licenses.json、应用版本和现有镜像/启动方式。可靠回滚采用对应的旧应用与升级前数据库/配置集合；恢复旧备份会放弃升级后的新队列、聊天、扫码保存和设置修改。不要在新旧服务同时写同一个 SQLite 的情况下试运行。

保留旧数据已足以升级，用户允许破坏性操作并不产生删库的必要。当前源码也没有可以安全自动合并旧版回写后视频字段的迁移工具。

## 合成验证证据

新增独立测试 `src/test/java/org/thornex/musicparty/service/LegacyMasterUpgradeTest.java`，没有改动应用运行源码。fixture 的 DDL 和 JSON 字段表来自准确旧提交源码；不是启动旧二进制导出，也不是读取用户生产数据。所有 Cookie、许可、账号绑定、队列和聊天均为临时合成值。

真实执行新版 RoomRepository、SystemConfigService、RoomRuntimeConfig、MusicQueueManager 与 MusicPlayerService；聊天与视频协作者使用无网络 Mock。四项验证：

1. 旧七表 schema 保持；房间标志、Cookie、许可权限、profile/bindings 和仍有效 admission 可读取，新增 ROOT 视频默认值入 global JSON。
2. 旧待播队列/优先级、音乐历史、聊天快照、播放锁经过新版保存及 SQLite 重开仍保留；真实音乐引擎恢复为空场景和暂停，进度锚点 0。
3. 旧字段 reader 可忽略新版新增字段，但旧字段再次序列化会丢视频与模式字段；默认严格 ObjectMapper 直接拒绝新增字段，证明回滚不能只根据 SQL schema 一致推断安全。
4. 非法旧 global 参数会阻止初始化，同时不擦除原房间或 payload。

执行：`./mvnw.cmd -o -DbuildDirectory=build/upgrade-17b5ad7-898c754-check -Dtest=LegacyMasterUpgradeTest test`，Java 21；2026-10-10 21:11:10 CST 完成，**4/4 通过，0 failures / 0 errors / 0 skipped**。报告位于对应隔离目录的 `surefire-reports`。这份伴随测试不被冒充为目标提交已包含的应用改动，也不代替 Ubuntu 真实部署和媒体验证。

## 增量包验收约束

包内应用必须能核验为完整目标提交 `8617b686c3842e00a159a67ea175a7fa5e9a1912`，并带对应前端资源和运行版本 `2.0.1-8617b68`。操作范围只覆盖应用/启动定义中必要部分；不得替换或重新下载 FFmpeg、重装 ncmapi、创建空数据卷、覆盖用户 config/许可/Cookie，也不得自动清旧 cache 或房间数据。

升级前应能发现并保留现有数据路径、配置路径、FFmpeg 路径和 ncmapi 地址，提供一致备份与恢复旧应用的方式。升级后以 8848 入口验证前端协议、许可重验、房间数量/设置、待播/历史、两平台取源及现有 FFmpeg/ncmapi；确认以上后才将“持久化兼容评估通过”提升为“Ubuntu 升级完成”。
