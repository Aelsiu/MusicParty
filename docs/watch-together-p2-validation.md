# 一起看视频 P2 实现与隔离验证

日期：2026-10-08（Asia/Shanghai）。实施依据：已批准的 [P2 v1 规格](watch-together-p2-implementation-spec.md)。业务实现已形成可评审结果；真实环境和发布兼容门槛尚未全部完成，本记录不等同发布验收通过。

## 隔离与最终协议

工作目录始终为 `C:/Users/93581/Documents/ChatGPT/MusicParty`，未操作 E:/dev。保留原有未提交业务修改，未业务提交、推送、部署或创建 PR；未启动/重启现有试用服务，未读取运行凭据、Cookie、许可值、密钥或用户媒体，未操作原数据库/房间播放。

当前实现继续 **protocolVersion=3** 增量契约；protocol2 的既有拒绝路径保持。原 P1 useVideo 预修改副本保存在 `build/p2-compat/useVideo-p1.js`，与未修改的当前 useVideo SHA256 相同：`5DAD21FE35CAB66294B4FCD2AA29A1CEE1780F2A864371816B804F3E458EB8B5`。执行该旧源码的 DASH/MP4 清源模拟测试 2/2 通过（暂停、清 DASH、remove src/load），说明当前代码路径可安全停止，但**不是实际旧 P1 浏览器构建的已缓冲验收**。该发布门槛仍待补；实际测试若不能停止旧源，应按规格成套升级 protocol4，不能仅改常量。没有声称当前 protocol3 已通过全部兼容发布条件。

## 实现结果

- P2-00：核对当前 P1 链路、保留旧 useVideo fixture；测试素材是合成数据。真实收藏响应与 MP4 档位映射没有足够证据，按保守边界实现并记录缺项。未在改动前完整重跑 P1 基线，因此263/192仍仅为历史记录。
- P2-01：统一 vote 自动评估遵守 skip lock、资格和媒体位置门槛；解锁、seek、成员变更和 tick 重评。本人票态/资格走私有 interaction，包含 stateRevision/interactionRevision，0 秒门槛合法。pending 禁止置顶，触屏按钮常驻，删除/无效操作保留解释。
- P2-02：VIDEO 历史 mailbox 元数据快照、20 条分页和 historyRevision 校验；界面待播/历史页签，重加走可信普通 enqueue，不直接抢播。自然结束记录、去重裁剪、历史兜底及双现场持久化规则保留。
- P2-03：ROOT 全局 cap 枚举、SQLite 保存后原子发布和运行期 policy revision，房间快照排除全局字段；按短边过滤 DASH/MP4 和全部 URL 别名，重算期限。同实例保持位置、倍速、意图及等待原截止。无合规源显式 policyRecoveryRequired 门闩，清源/ERROR/sourceRevision；recover 支持当前实例和无 playbackId pending，pause lock不阻止恢复，解析期间暂停意图保留。已成功实例的时长准入在 attempt 开始捕获，续期可继续；新实例/metadata提交按最新时长检查。
- P2-04：两种收藏链接白名单、固定官方 metadata endpoint、typed 元数据与不可变预览。预览10分钟/500条，批次最多min配置/100，metadata并发3/20秒单项/45秒总窗口。active+terminal最多32，active8且同身份1，terminal10分钟；统一 reservation/ticket，最终一次 mailbox 有序提交，每项终态守恒。原 browser/member/requestId 重连去重，异参冲突；发起session断线及模式/清理/凭据/来源边界取消，其他标签页断线不取消。普通单条模式切出后完成到原VIDEO语义保留。
- P2-05：前端历史/预览/逐项P选择/部分失败/查询原批次全接线，VIDEO旧绑定收藏入口及请求隐藏，MUSIC路径保留。未知或不可用结果不自动重发，须核对队列后重新预览选择；关闭面板保留批次。cap只限制实际来源，本端长期偏好不覆盖，设置位置保留；管理暂停使用playIntent。
- P2-06：隔离自动测试、生产构建、独立合并包和本记录完成；共享知识页/入口/测试元数据按当前源码同步并维护本地索引。

## 真实运行的自动验证

| 检查 | 结果 | 输出 |
|---|---|---|
| 最终后端离线全量 package | **297/297**；失败/错误/跳过均0 | `build/p2-package-final.log`、`build/p2-target/surefire-reports` |
| 后续新增 VIDEO 队列组合专项 | **3/3**；公平轮替/全局置顶不推进游标、在线优先回退、不同P、删除授权及历史兜底 | `build/p2-queue-tests.log` |
| 最终前端 node tests | **201/201** | `music-party-web` 的 npm test；前端代理最终运行证据 |
| 前端生产构建 | 通过，资源 `index-COEDx19f.js` | `build/p2-web` |
| 旧 P1 源码清源模拟 | **2/2** DASH/MP4 | `build/p2-compat` + video-engine.test.js；不等同真实浏览器 |
| 独立 Edge DOM fixture交互 | 两个全新浏览器上下文通过：1280×900 / 390×844，pageerror 0、横向溢出false；历史重加、pending触屏按钮、P2选择快照、部分失败与成功取消勾选 | `build/p2-ui-fixture/browser-evidence.json`、`ui-1280.png`、`ui-390.png`；合成数据/没有媒体 |
| 独立 P2 合并 JAR | ZIP 完整性/静态 index一致/JarLauncher 检查通过；44,669,256字节 | `build/p2/MusicParty-video-p2.jar` |

最终297项package已包含此前3项VIDEO队列专项及新增投票门槛跨越即时私发边界测试。此前专项结果单独保留作历史证据，不再叠加总数。政策34项/引擎40项/收藏协调器23项定向批次与全量重叠，不叠加总数。最终测试包含MUSIC/多房间隔离/持久化/协议/来源/等待、P2政策恢复和时长竞态、导入重连ACK丢失/冲突/取消/配额回滚/有界解析及历史schema/revision。首轮沙箱测试遇到临时目录原子替换/loopback权限及Byte Buddy attach问题，另有测试桩/期限fixture断言问题；修正fixture后使用独立临时目录、启动时javaagent和非fork Maven完成最终全量验证。

后端可复现（JDK21、缓存Maven、离线）：设 `JAVA_HOME=C:/Program Files/Java/jdk-21.0.12.1`，MAVEN_OPTS启动缓存byte-buddy-agent并指定`build/p2-temp`为java.io.tmpdir，执行缓存mvn.cmd `-o -DbuildDirectory=build/p2-target -DforkCount=0 package`。前端 `npm test`，`npm run build -- --outDir ../build/p2-web`；Windows沙箱构建使用独立临时目录并经审批运行。合并包：`python scripts/package-video-party-p2.py`。所有输出与现有target/dist/试用包分离。

## 新增与主要修改文件

后端新增：`VideoSourcePolicy`、`VideoFavorites`、`BilibiliFavoriteLinkParser`、`BilibiliVideoFavoritesService`、`VideoFavoriteMetadataResolver`、`VideoPreviewRegistry`、`VideoImportRegistry`。主要扩展 `VideoPlayerService`、`RoomPlaybackCoordinator`、`VideoApiController`、`MusicSocketController`、STOMP auth/disconnect、`PlayerState`、全局配置DTO/AppProperties/SystemConfigService、MP4/source API和QueuePersistenceService。MUSIC只补全局概要与既有测试fixture，没有顺改调度算法。

前端新增 `api/video.js`、`useVideoHistory`、`useVideoFavoriteImport`、`VideoFavoriteImport.vue`，扩展player/socket/constants、队列/搜索/设置/管理组件及systemConfig；useVideo保持原P1实现。新增后端P2/registry/provider/resolver/coordinator/policy/queue测试和前端video-import、store/engine/config回归。独立打包脚本 `scripts/package-video-party-p2.py`。

## 仍未通过的验收与限制

1. **真实收藏 metadata/错误/has_more/资源类型证据**：固定官方无Cookie探针未获得HTTP响应；provider全部为显式合成fixture。两种分享格式、空/私有/失效收藏、20/21条与500条实际边界、100条/45秒独立实际可用性需外网与合法收藏条件补验。详见 [收藏实现说明](watch-together-p2-favorites-notes.md)。
2. **MP4尺寸实证**：没有可信官方q映射；只接受该表示明确width/height。有限cap下未知MP4禁用，实际仅MP4可直连的视频可能被cap阻断，这是有意保守限制。未探测/下载媒体，未增加代理、缓存或FFmpeg链路。详见P1来源说明P2附录。
3. **实际旧P1构建清源兼容发布门槛**：模拟旧源码清源已过，实际已缓冲DASH/MP4旧浏览器构建未测；发布前必须补验或成套protocol4。
4. **独立浏览器完整交互**：已在全新Edge两个上下文执行桌面1280×900和390×844真实DOM fixture并人工查看截图，历史重加、pending操作、P2选择及部分失败面板通过；数据/控制为合成fixture，未接后端和CDN。完整应用的真实双观看身份、重连结果、ROOT/Owner入口与媒体政策仍待补；此fixture不代替集成实服务或手机验收。预览只用本任务127.0.0.1:18085，检查后停止，临时前端HTML入口已移除；原试用服务未动。
5. **继承真实设备债务**：HTTPS、跨网络、手机/iOS编解码和自动播放、扬声器连续听感、真实弱网多端提示，以及1080P连续2.5小时自然续期仍未补；未把受控clock/预算测试或本地窄屏当成通过。

所有CDN政策限制仅为应用不再提供/选择超限源，不能撤销已发出的签名URL或浏览器已缓冲字节。未发布当前独立包。知识维护结果见本地知识历史；用户于2026-10-08反馈验收基本通过，并授权保存本地项目版本。上述未专项确认的真实环境门槛仍保留，不从概括验收反馈推定通过。

知识维护实测：6主题 refresh 成功；musicparty-origin 显式index ready，最终5777节点/26173边，既有4处部分解析未消除，状态verified-anchors/changed=[]/graphReady=true。本地知识Git保存结果见本地历史。

最终合并包 SHA256：`A7C73DD4726932D52A40079998A40468287FE7A8A7A6381FCEAC5AA29AFBF1F7`。
