# 一起看视频 P2 实施规格

版本：v1；日期：2026-10-08；状态：已批准并进入实现；2026-10-08 已完成业务实现与隔离自动回归，真实环境及旧 P1 浏览器发布门槛待补，见 P2 验证记录。

本稿以当前工作树为基线，承接 [P1 规格](watch-together-p1-implementation-spec.md)、[P1 验证记录](watch-together-p1-validation.md)及其后端、前端、来源说明。旧路线报告和 P0 规格位于本机 `C:/Users/93581/Desktop/design/`，用于追溯目标；当前源码和已确认的 P1 规则优先于旧稿。工作区已有未提交修改，本次仅新增本设计文档，未操作试用服务、数据库或房间播放。

用户在本轮已确认：**P2 不做视频点赞；收藏功能仅做用户提交 B站收藏夹链接的导入，支持逐项选择与部分失败反馈，不做 MusicParty 内收藏夹管理。**

## 1. 范围、现状与交付边界

P2 的目标是完成视频队列互动的产品闭环。P0/P1 已实现的调度能力继续使用；新增可见历史、链接导入闭环和 ROOT 画质政策，并修补相关权限、结果反馈及配置边界。

| 旧路线项目 | 当前源码事实 | P2 工作性质 |
|---|---|---|
| 投票切视频 | `VideoPlayerService.skipToNext/evaluateVotes` 已有投票、撤票、资格分母；重新计票未完整遵守跳过锁 | 完善统一锁检查、本人票态及反馈，补 VIDEO 验收 |
| 置顶、删除 | 独立 VIDEO 队列复用 `MusicQueueManager`，已有 USER_TOP/GLOBAL_TOP 和授权删除 | 保留算法，完善 pending 状态、触屏按钮、无效操作反馈 |
| 公平随机、离线范围、循环 | 已有双模式独立设置、按成员轮替、置顶优先和历史兜底 | 补 VIDEO 组合验收和准确文案；不重写算法 |
| 历史 | 已有内部去重历史、SQLite 保存、空队列随机历史回放；无播放历史查询 API/UI | 新增浏览与重新点播；保留已有记录及回放语义 |
| 收藏夹导入 | 后端已有收藏夹 ID 导入、20 条分页、可信 P1 校验、配额预留及汇总失败；前端仍显示旧绑定收藏栏和 ADD ALL/SELECTED | 新增链接预览、逐项 P 选择、单批提交、逐项终态及重连核对；替换 VIDEO 旧批量入口 |
| 视频时长 | ROOT 系统参数已有 `videoMaxDurationMinutes`，默认 120，范围 1–1440 分钟 | 补动态变更策略与验收；修复当前片续期被降低上限误伤的边界 |
| 画质上限 | 后端、全局配置、管理 UI 均无上限字段 | 新增 ROOT 全局分辨率上限及来源过滤 |
| 视频点赞 | VIDEO UI 已隐藏音乐点赞，后端拒绝 VIDEO like | 按本轮确认排除；音乐点赞保持原行为 |

P2 不包含：新视频平台、B站直播、弹幕、会员/付费/番剧/区域限制扩展、画中画、原生全屏/锁屏或 Android 壳深度改造、应用内收藏管理、自动导入全部 P、视频媒体代理/缓存/下载/FFmpeg。音乐产品规则和其播放历史界面不因本规格扩大范围。

### 1.1 继承 P1 的硬约束

- 一个房间一个协调器、一个 mailbox、一个激活模式；MUSIC/VIDEO 队列、当前现场、设置、历史分别保留，切回保持暂停。
- 本端画质独立选择，入口仍在“设置 → 其他设置 → 歌词提前显示”下方；房间倍速仍为 0.5/0.75/1/1.25/1.5/1.75/2，仅 Owner/Root 修改。
- 当前切 P 仅 Owner/Root，尊重跳过锁；新 P 从 0 起播、新 playbackId，保留倍速和播放意图。普通成员可选择 P 入队。
- 合格观看端持续真实缓冲 2 秒触发等待；单轮屏障从建立时起最多 15 秒。超时顶部提示显示服务端成员名，同一 playbackId 超时端不自动再取得阻塞资格。
- 等待期间 seek/rate/来源变化推进 waitRevision，但不延长原截止；手动暂停取消自动续播。播放意图与有效暂停分别处理。
- 浏览器直连可信 CDN；Cookie 不给成员、不进聊天/日志，完整签名 URL 不持久化、不进入结果或诊断。操作结果只携带元数据。

## 2. 产品入口与界面行为

### 2.1 队列与历史

VIDEO 的现有右侧队列及手机队列抽屉增加“待播 / 历史”页签。默认待播；切房间、实际切媒体模式时回到待播并清理旧展开/选择状态。聊天历史仍用原入口。

待播显示视频标题、选定 P、UP 主、点播人、可信时长和优先级。未成功起播的 queue-origin pending 在原 queueId 下显示“正在解析”或“来源失败，可重试”，继续占队列及个人配额；不显示音频下载进度或“下载失败”。pending 不允许通过置顶打断解析，按钮禁用并说明“已进入播放准备”；删除/清理仍可用且须使旧解析失效。

触屏端置顶/删除使用常驻按钮或明确操作菜单；不依赖 hover。删除提示使用“待播视频”，优先级标识区分“个人优先”和“全局置顶”。当前正在播放的视频不作为待播项被删除。

历史按最新记录在前，分页 20 条，显示标题、P、UP 主、封面和时长，提供“重新点播”。现有历史没有点播人、播放日期、播放完成率，不虚构这些信息。重新点播走普通可信入队检查，归当前操作者，从所选 P 的 0 起播；历史条目本身仍保留，不直接抢占当前播放。VIDEO 游客可浏览；重新点播须命名成员。

### 2.2 收藏夹链接导入

VIDEO 搜索弹窗提供“视频搜索 / 收藏夹链接导入”。导入不要求绑定 B站账号；VIDEO 隐藏旧账号绑定收藏栏、旧 ADD ALL 和逐条循环发送的 SELECTED 路径。MUSIC 的账号绑定、歌单和收藏导入继续保留，存储键 `bilibili` 不迁移为 `bilibili-video`。

导入流程：粘贴链接 → 预览收藏夹 → 分页浏览/勾选 → 为每条明确 P → 提交一批 → 查看逐项结果。面板保持打开，不能把发送成功、2 秒本地 pending 或 toast 当作实际入队成功。

- 输入只支持本规格定义的 HTTPS B站收藏夹链接（§5.1），预览前显示允许格式。禁止跟随短链接或任意 URL 抓取。
- 每页 20 条；滚动或“加载下一页”才继续。全选仅指已加载、可选择的候选，并显示“已选 X 条”；不承诺扫描全收藏夹。
- 失效/无权限/不支持的候选可见且禁用，显示安全原因；不能静默过滤失效项导致页面数量和分页错位。
- 默认每条选择 P1；展开条目调用现有可信 metadata 获取全部 P，允许换为另一 P。一次选择每个视频一个 P；想加入多个 P 用后续独立入队操作。界面明确提示“不自动导入其余 P”。
- 聚合时长仅供参考；只有可信选定 P 的时长决定可选。未知或旧政策下的标记显示“提交时重新校验”。
- 提交按钮展示本批大小、当前可用配额和有效批次上限。超过上限不截断，要求缩小选择；配额提示只作参考，以提交时服务器判断为准。
- 结果逐项展示“已入队 / 重复跳过 / 配额不足 / 时长超限 / 来源或内容失败 / 已取消”等。只有可重试失败项可再次勾选；成功项取消勾选。临时失败以新 requestId、新批次重试失败项；结果未知时先查询原批次，不能直接重发为新批。
- 关闭面板不取消已经接受的批次；再次打开可查看当前浏览器本房间的最近结果。切模式/房间后的旧结果不弹出成功提示、不污染新列表；取消结果可在回到原房间 VIDEO 后查询。

### 2.3 管理入口

`AdminDashboard.vue` 继续管理当前房间的激活模式：播放控制、三把锁、投票配置、公平随机、离线范围和清理。VIDEO 文案使用“视频/条”，隐藏 MUSIC 专属 FM/DJ/直播卡片。暂停按钮按 `playIntent` 显示；缓冲等待中点击“暂停”必须修改手动意图，不能显示成普通“恢复播放”。

`RoomManager.vue → 系统参数` 继续只对 ROOT 开放。已有视频时长保持，新增“视频分辨率上限”选择器（§6）；不得放在房间播放控制或本端画质偏好中。Owner 只能查看当前生效限制的概要，不能修改全局参数。

## 3. 角色、锁与投票规则

| 操作 | 游客 | 命名成员 | Owner/Root |
|---|---|---|---|
| 观看、观测、RESYNC、历史/收藏预览 | 允许；预览不授予入队权 | 允许 | 允许 |
| 单条/历史/收藏批次入队 | 拒绝 | VIDEO 激活、来源启用、可信元数据/配额合格时允许 | 同普通入队规则，不绕配额 |
| 置顶 | 拒绝 | 可置顶任意待播条目，沿用既有规则 | 同普通按钮语义 |
| 删除待播 | 拒绝 | 仅自己的条目 | 可删除任意条目 |
| 普通播放/暂停、播放模式循环 | 拒绝 | 分别受 pauseLocked/modeLocked | STOMP 普通按钮同样受锁 |
| 普通“下一个” | 拒绝 | 点播人直接切；他人按投票设置；受 skipLocked | STOMP 普通按钮同样受锁/投票；管理台明确“强制切换”另走管理操作 |
| 当前切 P、房间倍速 | 拒绝 | 拒绝 | 允许；切 P 仍受 skipLocked，倍速按 P1 权限 |
| 三把锁、清队列/离线/指定人、重置、切模式 | 拒绝 | 拒绝 | 验证本房间许可后允许 |
| 公平随机/离线范围 | 拒绝 | 无公开控制入口 | 现有管理 HTTP 操作；不增加普通 STOMP 开关 |
| 全局时长/分辨率限制 | 拒绝 | 拒绝 | 仅 ROOT，Owner 拒绝 |

管理 HTTP 经协调器使用既有 SYSTEM 执行身份，显式管理控制可覆盖普通播放锁；它仍须匹配上下文/实例及有效管理许可。不能把一个 STOMP 管理标记当成 SYSTEM。当前切 P 的 skip lock 特例继续按 P1 执行。pauseLocked 沿用既有“禁止普通手动暂停、允许恢复”的含义，不把锁扩成禁止一切恢复；系统暂停、模式切换和等待仍按生命周期规则处理。

三把锁只约束各自播放控制，不新增“入队锁”或借播放模式锁禁止收藏导入/置顶。自然结束按原播放模式推进，skip lock 不禁止自然结束；手动暂停/全员等待仍停止自然计时。

### 3.1 投票切视频

保留既有产品语义，不改为点赞/排行榜：

1. 点播人点击“下一个”可直接跳过；其他命名成员在投票启用时点击为投票，再点为撤票。投票关闭时普通“下一个”直接切换，仍受锁和现有限流。
2. 资格集合是当前在线、最近活跃的命名成员 token 集合，排除当前点播人和游客。同 token 多连接一票；短断线宽限 token 不进入投票分母。分母为 0 时不自动达到阈值。
3. `票数 / 资格人数 >= voteSkipThreshold` 才切换；资格成员离开/加入、票变更和配置改变后重新核算。无资格票被剔除，未实际开始的新实例不继承旧票。
4. 投票等待沿用**当前视频媒体进度达到 voteSkipWaitTime 秒**，不是墙钟倒计时：倍速会改变达到门槛的现实用时；手动暂停和缓冲等待冻结进度。seek 可跨过/退回门槛，评估时再次检查当前媒体位置。该选择保留当前语义，不引入新的计时政策。
5. skipLocked 为 true 时拒绝普通投/撤票及普通切换，并禁止成员变化、源提交等自动评估触发 next；保留已有票。解锁时在当前 VIDEO 重新核算，含时间门槛。修改所有锁也走同一评估入口。
6. 手动暂停时允许到达媒体时间门槛后的显式投票；达到阈值只换当前片，保持 PAUSED，不自动播放下一条。等待期间达阈值则清理旧实例/屏障，下一条按原 PLAYING 意图准备。
7. 切 P/下一条/单条循环新实例清票；同实例续期、换画质不清票。模式切出保留 VIDEO 票态，inactive 不评估；切回保持暂停，成员资格重新核算并服从锁。

公共快照继续给总票数/资格人数。新增本连接私有互动快照 `canVote / hasVoted / voteWaitRemainingMs / reason`，附 epoch/modeEpoch/playbackId/stateRevision 和运行期单调 interactionRevision；公共 state 不携带个人票态或成员 token 列表。cast/revoke、资格、锁、seek、实例和相关设置变化后私发/重取；tick 中跨越媒体门槛使 canVote 改变时也私发，避免界面永久禁用。等待剩余量是尚差的媒体毫秒，UI 标注“播放至 X 秒后可投票”，不伪装现实秒倒计时。`voteSkipWaitTime=0` 是合法值，前端不能用 `|| 15` 覆盖。

### 3.2 置顶与公平随机

- SHUFFLE 中同一普通项首次置顶为 USER_TOP，再次提升 GLOBAL_TOP；SEQUENTIAL/REPEAT_ONE 中直接 GLOBAL_TOP。已 GLOBAL_TOP 返回明确的无变化结果。
- GLOBAL_TOP 优先于成员轮替；取全局置顶不推进公平轮替游标。公平随机按成员轮替，成员轮次内 USER_TOP 优先，其他候选随机。不改为全队列等概率抽取。
- 关闭离线范围的当前语义是“在线优先”：存在在线候选时优先在线组，无在线候选则回退全体；文案应说明该回退，不能承诺永久排除离线点播。
- pending 已被选择不再参与抽取，置顶无变化；清理/删除它使异步源结果失效。置顶/公平设置本身不切当前视频、不改变 P1 源/倍速/等待。

## 4. 队列、历史与持久化

VIDEO 使用 `VideoQueueManager` 的独立实例；身份仍为 platform=`bilibili-video`、canonical id=`BV…:pN`，queueId 标识一条待播记录。可信 cid、P 标题、时长只能由视频 API 获取，不接受客户端伪造。

房间/个人限额按**逻辑 queue + queue-origin pending + 准入 reservations**计算。已成功起播的当前片不在待播配额中；同 canonical id 的重复入队按既有待播去重规则拒绝，不能把不同 P 误认为重复。历史重加和收藏导入使用同一准入规则。

历史继续只记录自然结束且非 REPEAT_ONE 的视频；手动跳过、失败和单条循环不记。平台+canonical id 去重，最新放前；最大容量为 0 时允许空历史。空队列的历史随机回放保留，显示“历史记录”，不属于任何成员配额，不把 history-origin pending 保存成用户队列。浏览历史不改变此调度。

新增运行期 `videoHistoryRevision`，每次历史内容变化（新增/去重重排、裁剪、reset/restore）递增；在当前 playbackEpoch 下比较，不能跨重启比较。历史 GET 在 mailbox 内取得不可变快照，再分页序列化。

持久化继续 SQLite `snapshotVersion=2`：MUSIC queue/history、VIDEO queue/history、两份设置与模式分别保存。仅浏览历史无需迁移历史数据类型；缺视频字段仍按旧兼容规则恢复。pending 合并到对应队列首部，原 queueId 恰好一次；恢复保持暂停，当前片/进度不恢复。

投票、预览、导入运行任务/结果/去重账本、historyRevision、系统政策运行版本、临时源和等待均不写房间媒体快照。新增分辨率限制写全局 system_config，不写每房间设置。成功入队表示内存队列已提交；队列沿用周期/生命周期保存，不能承诺机器立即故障时尚未保存的批次一定恢复。模式切换仍须严格保存成功后切内存。

## 5. 收藏夹、历史与互动协议

### 5.1 收藏夹链接与预览契约

P2 支持以下**产品输入格式**，实施时用脱敏真实链接/响应补 fixture 验证；此表不宣称覆盖 B站所有历史分享格式：

| 输入 | 解析 |
|---|---|
| `https://space.bilibili.com/<数字UID>/favlist?fid=<正整数>` | 提取唯一 fid；UID 仅为链接描述，不授予权限 |
| `https://www.bilibili.com/medialist/play/<数字UID>?business=space&business_id=<正整数>` | business 必须为 space，提取唯一 business_id |

使用 URI parser、精确主机/路径白名单，禁止 userinfo、非标准端口、片段、重复关键参数、短链、任意 CDN 和其他站点；正整数长度有界且无前导零/正负号。附加的非关键分享参数忽略，绝不拼接到上游 API。预览服务只使用解析出的收藏夹 ID 请求固定官方 metadata API，不 fetch 用户 URL。

新增视频专属 typed DTO/API，不能把旧音乐 provider 的空列表当成“真实空收藏夹”。复用现有 Cookie/WBI 请求设施和官方收藏 metadata 请求经验，保留受限访问/风控/临时错误的区分，不复用旧 `INVALID_SKIP` 或任意原始响应文本作为客户端契约。

| 路由 | 请求/响应要点 |
|---|---|
| `POST /api/video/favorites/preview` | `link` 与 expected epoch/VIDEO/modeEpoch；membership、protocol3、VIDEO 激活、B站 enabled；建立 previewId，返回 folderId/title、第一页、nextPage、expiresAt 和服务端上下文 |
| `GET /api/video/favorites/previews/{previewId}?page=N` | 当前准入+签名浏览器身份，匹配原 room/epoch/modeEpoch；20 条一页，正整数 page；返回 items、hasMore，不因过滤失效项推断页尾 |
| `GET /api/video/history?page=N&limit=20&historyRevision=…` | membership、protocol3、expected epoch/VIDEO/modeEpoch；首请求可不带 revision，后续不匹配返回 HISTORY_CHANGED，UI 重新第一页；返回 historyRevision/total/items/上下文 |
| `/app/video/import/favorites` | named member；requestId/expected 上下文、previewId、items；接受后私发 IMPORT_ACCEPTED 与 operationId；网络工作不阻塞 mailbox |
| `/app/video/import/status` | 本房间命名成员，携 operationId 或原 requestId；验证所有权并返回当前/终态；不创建任务、不需要当前 modeEpoch 仍等于原批次 |
| `/user/queue/video/import` | 私有批次结果，订阅纳入白名单；禁止公共广播逐项个人结果 |
| `/app/video/interaction/resync` → `/user/queue/video/interaction` | 仅读当前连接票态和资格；游客返回不可投票，不授予控制权限 |
| `/app/video/control/recover` | 显式政策来源恢复；requestId/expected 上下文、playbackId/sourceRevision，或 pending 的 null playbackId + queueId；规则见 §6.2 |

新增 HTTP 请求一律带 `playback-protocol: 3`、`X-Room-ID`、`X-Room-Token`（管理 HTTP 按既有验证路径补 Authorization）；expectedPlaybackEpoch、expectedMode、expectedModeEpoch 在 POST JSON 中，在 GET query 中使用相同完整字段名。POST preview 再带只用于响应关联的 requestId，GET 页读带 query requestId；服务端读不缓存为变更命令。预览分页 GET 同样携 expected 上下文，不能只凭 previewId 跳过版本检查。成功读响应均带 roomId/protocolVersion/playbackEpoch/mediaMode/modeEpoch/requestId；clientGeneration 只在本端捕获，不上传。interaction/resync body 带 requestId 与三项 expected 上下文，只读且不纳入 toggle 去重缓存。上述 mutation/result 协议受 §7 最终协议门槛统一约束。

每个 preview item 含稳定 `itemId`（预览内原始序号/资源标识）、bvid（可空）、title、cover、ownerName、参考时长、selectable、reasonCode。资源类型/失效内容明确分类；metadata 的 selected P / withinDurationLimit 只是选择辅助，提交必须重新校验。预览不含媒体源。客户端给 items=`[{itemId, page}]`；不上传 cid、时长、标题或签名 URL。

预览绑定 room、playbackEpoch、modeEpoch 和服务器签名浏览器身份（复用已有 HttpOnly watcher Cookie 的验证属性，仅作隔离键，不修改等待资格）；有效期 10 分钟。每个浏览器身份每房间一个预览，房间最多 16 个，每预览最多缓存 500 条候选。到达上限明确显示只浏览前 500 条；过期或旧模式要求重新预览。上游页面不是稳定数据库快照：已加载候选作为此次选择范围，入队时再验证内容可用性，不承诺收藏夹后来增删同步到旧预览。

游客可预览但需命名后重新确认提交；预览本身不占入队 quota。HTTP 的 room admission、浏览器身份与 STOMP 连接必须一致；不得接受请求体伪造身份属性。

### 5.2 批次、逐项结果与幂等

有效批次上限为 `min(maxPlaylistImportSize, 100)`，100 是本轮固定工程预算，UI 明示；超过返回 IMPORT_BATCH_TOO_LARGE，不静默 take。同成员/签名浏览器身份一次最多一个运行批次，每房间最多 8 个；无槽时返回 IMPORT_BUSY。metadata 并发最多 3，总网络窗口 45 秒、单请求沿现有 20 秒上限；预算到期的未完成项是可重试失败，不丢已得到结果。

处理顺序：

1. 在 mailbox 校验 named member、预览所有权和期限、expected 上下文、B站 enabled、items 唯一与预览范围/P 格式、批次上限。无合法项/零可用配额立即拒绝。
2. 在网络前同时预留 operation registry 条目、completion ticket 和 `min(请求有效项数, 当前可用配额)` 个 slot；任一预留失败释放已取得资源。接受时复制不可变选择快照（itemId → bvid/P），依用户所选原列表顺序分配 slot，超出部分作为 QUOTA_EXCEEDED 结果。reservation 与 pending 使用统一计额，不借异步腾空配额。之后预览到期/被新预览替换只拒绝新提交，不取消已接受批次，最终提交不再次依赖预览缓存。
3. 网络阶段仅获取可信 descriptor，不解析媒体源、不下载视频。结果不可变，保留提交顺序；每条选定 P 独立检查时长/内容。预览不是信任时长的理由。
4. 最终通过原 mailbox 一次提交：重验原 room 生命周期、VIDEO 激活与原 modeEpoch、来源 enabled、操作者仍为本房间命名成员/同验证身份及最新准入政策。释放本批 reservation 并在同一个任务内按序检查最新 quota/重复，再加入成功项；其他项产生明确结果。
5. 一次推进队列版本并给请求者终态。所有项的结果数之和必须等于原请求项数；零成功也是终态，不能使用 SUCCESS toast。

结果结构含 `protocolVersion=3, roomId, playbackEpoch, mediaMode=VIDEO, modeEpoch, requestId, operationId, operationRevision, status, acceptedAt, resultExpiresAt, counts, items`。运行状态为 ACCEPTED/RESOLVING；终态为 COMPLETED/PARTIAL/FAILED/CANCELLED。item 含原 itemId、canonicalId（已验证时）、状态/安全 reasonCode/retryable、成功的 queueId；无媒体地址、Cookie、原 sessionId 或管理 token。operationRevision 随阶段单调增加；客户端不能被迟到 ACCEPTED 覆盖终态。

逐项终态：ADDED、DUPLICATE、QUOTA_EXCEEDED、DURATION_EXCEEDED、INVALID_SELECTION、CONTENT_UNAVAILABLE、CREDENTIALS_REQUIRED、CREDENTIALS_INVALID、TEMPORARY_FAILURE、CANCELLED。counts 固定为 requested/pending/added/skipped/failed/cancelled：DUPLICATE、QUOTA_EXCEEDED、DURATION_EXCEEDED、INVALID_SELECTION 计 skipped；凭据、内容和临时失败计 failed；六项中除 requested 的五项之和等于 requested，终态 pending=0。全 added 为 COMPLETED，部分 added 为 PARTIAL；提交前上下文取消为 CANCELLED，其余零 added 为 FAILED（全重复可显示警告“均已在队列”，不报系统异常）。消息用稳定枚举映射，禁止直接打印上游异常正文。已验证但在最终提交时被降低限额挡住的项返回相应失败，不能超额提交。

HTTP 错误统一 `{ok:false, code, message, retryable}`，使用适当 400/403/409/429/503 状态码；message 为安全产品文案。私有 import 消息增加 kind=IMPORT_ACCEPTED/IMPORT_RESULT/IMPORT_STATUS/IMPORT_ERROR，前三者包含上述操作结构，错误含 requestId/operationId（已知时）及同样的安全错误对象/roomId/上下文。status 查询 body 为 `{expectedPlaybackEpoch, operationId}` 或 `{expectedPlaybackEpoch, originalRequestId}`，须匹配当前运行期与原房间/身份，但不要求当前媒体模式/模式代次等于原批次；读出的原 mediaMode/modeEpoch 不用于修改当前现场。私有互动响应完整字段为 protocolVersion/roomId/playbackEpoch/mediaMode/modeEpoch/playbackId/stateRevision/interactionRevision/canVote/hasVoted/voteWaitRemainingMs/reason；reason 为 ELIGIBLE/GUEST/ENQUEUER/LOCKED/DISABLED/WAITING/INACTIVE，无当前实例时 playbackId=null、canVote=false。

现有 coordinator 的 512 项缓存仅对 sessionId 内请求去重，**不足以覆盖断线后提交成功而 ACK 丢失**。新增视频导入 operation registry 在原 checked/dispatch 前处理该操作的幂等准入，键为当前 playbackEpoch + 原 room + 服务端验证浏览器身份 + named member token + requestId；不信任请求体 token，不改变其他命令的缓存键。

- 同 key/同规范化参数返回原 operationId 和最新结果；不同参数返回 REQUEST_ID_CONFLICT。新连接用相同浏览器验证身份和成员身份恢复原操作，先验会员资格/所有权，再查账本；查旧结果不再次应用变更。
- active 任务不得被 LRU 淘汰；**active + 未过期 terminal 合计最多 32 条**，接受时预留的条目原位转成终态，满时拒绝新批次，不提前丢弃账本。每个终态保留 10 分钟，期限从终态建立时算，必须覆盖对应预览仍可能提交的全部有效期；重试原 key 不延长预览/结果期限。
- 到期删除完整条目；删除后无法区分从未存在和已经过期，查询统一返回 IMPORT_RESULT_UNAVAILABLE，不创建任务。原 preview 此时必须已过期/被替换，重新提交原 payload 由 PREVIEW_EXPIRED/PREVIEW_NOT_FOUND 拒绝，不能因账本删除再次准入。不承诺超过保存窗后永久记住 requestId。前端根据已知 resultExpiresAt 显示过期，并先核对队列，不能将“结果不可用”自动解释为未执行；新的 preview/选择用新 requestId。
- 重启改变 playbackEpoch，原运行任务/账本失效；不宣称跨重启 exactly-once。原 requestId/epoch 直接拒绝，队列先 RESYNC，由用户决定后续失败项；不得自动重新导入整批。
- 不同身份不能凭 operationId 取结果。验证浏览器身份用于此功能的隔离，不等同“每物理设备一个人”；同浏览器同成员标签页共享这份批次边界。

### 5.3 取消与旧上下文

新链接导入在最终提交前采用整批 commit：切模式、关闭/删除房间、清队列、清该点播人、来源关闭或对应 Cookie revision 改变时取消未提交任务、释放 slot/ticket、标记未提交项 CANCELLED；迟到网络结果不得复活。清离线队列包含离线点播人的运行批次。服务端绑定**发起 sessionId**，仅该连接断线取消尚未提交批次；同身份其他标签页断线不取消。若 commit 已先执行，重连只取成功终态。sessionId 只在服务端保存。取消也须保存可查询的短期终态，房间关闭后没有访问权时返回不可用。

Cookie 变化不清已有成功队列。已提交批次的结果仍陈述原事实，不能把后续删除追溯改成失败。取消/结果逻辑在 mailbox 内完成，不能从 Reactor 回调直接写任务状态。

**既有普通单条/旧收藏夹 ID 准入是另一条已接受语义**：当前测试允许已接受 metadata 在模式切出后完成到原 VIDEO 队列，不能为了新链接批次取消政策把所有入队统一取消。本轮保留普通单条语义与模式归属；VIDEO 旧 ID 导入不再有 UI 入口，兼容服务端旧调用仍固定原 VIDEO，沿其原策略。新增链接批次严格按本节取消，不改 MUSIC 导入。

## 6. ROOT 分辨率政策与时长热更新

### 6.1 上限定义与配置

新增全局 `videoMaxResolution`：`UNLIMITED / 480P / 720P / 1080P / 1440P / 2160P`。部署默认和旧 system_config 缺字段默认 UNLIMITED，保持 P1 可用来源范围；ROOT 可主动选择有限上限。这是分辨率上限，**不限制 fps、codec 或音轨码率，不统一各端画质**。

按视频编码尺寸短边判断上限（横屏 1920×1080、竖屏 1080×1920 同属 1080P），允许值对应短边最大 480/720/1080/1440/2160；不能按画质标签、qn 数字大小、请求 qn 或 B站 accept_quality 宣称合格。DASH 使用已验证 width/height；有限 cap 下尺寸未知/无效的表示拒绝。UNLIMITED 仍遵守原来源格式与安全校验。

MP4 回退必须有**该流**的可信尺寸证据，扩展 `Mp4VideoSource` 可空 width/height：优先官方该表示尺寸；若来源不提供，只有经本次官方响应/fixture 核实的 q 档位映射才可作为保守边界，且视频原始尺寸不能冒充降档流尺寸。P2 初始可只覆盖已实测的普通 MP4 档；证据不足的 MP4 在有限 cap 下禁用，不能悄悄用不明分辨率绕上限。该缺口须在实施第一阶段查明，不增加媒体探测下载或 FFmpeg。

配置批量校验/SQLite 保存/原子发布沿用 `SystemConfigService`；无效枚举拒绝整批，Owner/房间本地配置不得覆盖。新增运行期 `videoPolicyRevision`，原子发布时递增，与 cap 快照一起捕获；新进程重新建立，不能跨 epoch 比较。HTTP ROOT 更新成功表示全局值已持久化/发布，各活跃房间按 mailbox 收敛；短暂尚未应用的房间必须在源提交/快照出站再次检查最新 cap，不得以 fanout 成功作为唯一过滤条件。

### 6.2 来源和前端执行

- 在解析后、commit 前、授权快照生成时三处应用同一纯策略过滤；不改签名 URL，不请求媒体字节。只下发 cap 内的 DASH 视频表示及允许 MP4，保留完整兼容音轨。过滤须同时更新 videoSource.dash/videoSource.mp4/videoSource.url/backupUrls 与 nowPlaying.music.url 等全部别名，不能从 legacy fallback 泄漏被移除的源。
- 根据过滤后的音/视频/备源重新计算 quality、expiresAt、hasUnknownExpiry；不能因为移除轨道沿用错误的新鲜期。网络 attempt 捕获 Cookie/source/policy 上下文，回调以最新政策重新过滤，旧 cap 不能覆盖新 cap。
- 降低 cap 时在 mailbox 用提交时的位置裁剪 currentSource；若可播放集合变化，保持 playbackId、位置、playIntent 和 rate，sourceRevision 只推进一次。快照读取前若发现引擎尚未应用最新 videoPolicyRevision，也在同一 mailbox 执行这次提交，不能只临时过滤出站 DTO 而维持旧 sourceRevision。已有等待用 P1 revise 逻辑撤销旧 READY、保留 frozenPosition 和原 15 秒截止；同实例超时排除不清。
- 提高 cap 只更新限制概要，不为所有房间强制重新取流/提升画质；下次正常续期或显式合法刷新才取得新增表示。本端长期偏好仍保留。
- 客户端设置显示“偏好 / 实际 / 系统上限”，在可用、兼容、可达且 cap 内的集合中选择；长期 1080P 偏好遇到 720P cap 时降档并说明原因，不能覆盖存储偏好。音乐模式可保存视频偏好，入口不移动。
- 政策更新导致换源时 useVideo 旧 sourceGeneration/探针/play Promise 失效，按房间锚点恢复；真正缓冲仍按 2 秒/15 秒处理，不给政策操作另开等待预算。

若有限 cap 下没有任何受支持的视频表示/MP4：保留当前 descriptor 和 playbackId，冻结当前位置，sourceStatus=ERROR、安全 sourceErrorCode=VIDEO_QUALITY_LIMIT，清理不合规 currentSource；不自动 next，不替换为超限来源。wire 快照必须给 `nowPlaying.videoSource=null`、`nowPlaying.music.url=null`、有效 `isPaused=true`，并推进 sourceRevision，不能只更新 ERROR 文案。取消已有等待（不是弱网超时，不发超时提示），保留同实例超时排除及手动意图。

新增公开 VIDEO 字段 `policyRecoveryRequired=true` 作独立恢复门闩，位置/意图冻结条件纳入它。后续心跳、cap 提升、Cookie 更新和背景错误刷新都不能清门闩或自动恢复。前端错误面板提供独立“重新获取视频源”按钮，调用新增 `/app/video/control/recover`（named member、requestId/expected 上下文、playbackId/sourceRevision）。恢复不受 pauseLocked 阻止，因为该锁沿用禁止普通暂停、允许恢复的既有语义；不绕过来源启用、时长、cap、限流或实例校验。该命令只明确授权一次政策恢复解析，不反转 playIntent；成功提交最新合规来源才清门闩，按提交时意图继续 PLAYING 或保持 PAUSED；失败仍阻塞。解析期间的手动暂停能覆盖恢复意图。普通暂停按钮继续只按意图切换，不能要求用户为恢复错误连点两次。后台 refresh 与显式 recover 分开，不能仅信任客户端自由文本 reason 来清门闩。

首次解析尚未成功、没有 playbackId 的 pending 同样可被政策挡住：公开 `videoPending={queueId, canonicalId, origin:QUEUE|HISTORY}`，标记 sourceErrorCode/policyRecoveryRequired，保留 pending/配额，不虚构当前媒体。recover 带 playbackId=null 和该 queueId，服务端核对仍为同一 pending；成功才按正常首播建立实例、从 0 起播。删除/next/reset/切模式清理此恢复门闩，迟到旧恢复无效。普通来源临时错误沿 P1 重试；政策错误采用本节显式恢复，不能混成同一退避自动播放路径。

媒体直连的政策限制是“应用不再提供/选择超限源”：已发出的 CDN 地址及浏览器已缓冲字节无法撤销，不能承诺立刻让旧客户端或外部下载地址失效。正常客户端收到 sourceRevision 后按新集合重建；这不是 CDN 授权或 DRM。

### 6.3 时长降低的精确边界

保持视频时长独立于 B站音乐时长，选定 P 判定，不用聚合时长。

| 对象 | 新时长上限生效行为 |
|---|---|
| 新入队/历史重加/收藏最终 commit/当前切 P | 以最新上限重新验证，超限拒绝 |
| 已在队列或 queue-origin pending | 保留条目及配额，不自动删除；正式准备新实例时再次检查，超限进入可解释错误，保留 pending，允许删除/跳过/上调后重试，不自动 next |
| 已成功起播的当前视频（含切模式保留的同一现场） | 本片当前实例继续；同实例续期/凭据更新重新取源允许其可信已准入时长，不能在几分钟后的续期突然截断。cap/来源安全校验照常执行 |
| 自然结束后的下一次播放（含 REPEAT_ONE 新实例） | 重新受最新上限；超限不自动循环，保留可恢复现场并暂停，提示原因 |

实现需把可信当前实例的时长准入与新实例准入分开，不能删除 `resolveSource` 的全部时长检查，不能接受客户端“已准入”标记。既有 pages.withinDurationLimit 需随政策重新计算，不能缓存为永久许可。恢复旧队列也按新实例规则验证。

## 7. 协议兼容、版本屏障与发布

本规格采用 **protocolVersion=3 的增量扩展**：新增路由、DTO、私有订阅、配置概要、sourceErrorCode；现有播放/等待字段与控制语义不替换。新增 `videoFeatures`（history/favoriteLinkImport/interactionState/resolutionPolicy）能力概要，前端只在匹配能力下开启新入口；不把后端缺路由误报为空历史或导入成功。

精确快照位置：PlayerState 顶层 videoFeatures 为上述四个 boolean；顶层 sourceErrorCode 为可空安全枚举、policyRecoveryRequired 为 boolean、videoPending 为可空 §6.2 对象、videoHistoryRevision 为非负运行计数。`config.videoMaxResolution` 是 §6.1 枚举，`config.videoPolicyRevision` 为全局运行期非负计数。同模式历史内容变更推进 videoHistoryRevision，历史页签打开时据此重取第一页。旧服务/旧快照缺失时 features 全 false、error/pending 为 null、门闩 false、history/policy revision 为 0、cap 为 UNLIMITED；新后端必须明确发字段，不让缺省被误当能力支持。MUSIC 顶层 VIDEO 运行字段为空/false/0，config 仍可展示全局视频限制，不影响 MUSIC 时钟或源。

旧 protocol 2 仍在取源前拒绝。旧 P1 protocol 3 页面继续使用原按钮和按 sourceRevision 换源，服务端仍过滤所有授权来源；不承诺旧页面有完整的新政策文案/导入结果界面。旧 P1 自动/用户 refresh 不能清政策恢复门闩，返回 POLICY_RECOVERY_REQUIRED 和“请刷新页面使用新版恢复入口”的安全提示，或由同房间新版成员合法恢复；不得借旧自由文本 reason 解锁。发布前用实际旧 P1 构建验证已缓冲 DASH/MP4 的 source 清空、ERROR 和 sourceRevision 行为；若不能正确停止旧源或安全收敛，实施阶段必须升 protocol4 并同步所有 STOMP/HTTP/header/DTO/前端屏障与升级提示，**不能只改一个常量**。这是兼容验收门槛，协议最终值在实施首步记录。

所有变更仍带 requestId、expectedPlaybackEpoch、expectedMode、expectedModeEpoch；next/pause/seek/rate/page/refresh/recover 继续匹配 playbackId，来源报告匹配 sourceRevision。新增 history/preview 读响应也携服务端上下文，前端捕获 roomId/clientGeneration/epoch/modeEpoch/界面生命周期及请求序号。陌生 epoch 只能由当前连接完整 state 建立，不能由新结果事件建立同步状态。

私有互动状态只在当前连接已同步且 epoch/modeEpoch/playbackId 匹配时应用，按 interactionRevision 去重；其 stateRevision 尚在公共状态之前时短暂待接收/RESYNC，公共状态追上后才启用。后续同实例普通心跳不清本人票态，但实例/模式/连接改变立即清理；旧私有响应不能覆盖新票态。

新批次结果可独立查询旧 modeEpoch 的终态，但不能因此更新播放器/队列或在当前 MUSIC 弹成功提示。公共 state/queue 仍通过同一版本屏障；乱序导入结果、私有票态和旧 HTTP 响应不能绕过它。

## 8. 文件级实施顺序

| 阶段 | 文件与变更 | 完成门槛 |
|---|---|---|
| P2-00 现状回归与契约 fixture | `VideoPlayerServiceTest`、`RoomPlaybackCoordinatorTest`、`QueuePersistenceProtocolTest`；补脱敏收藏响应/链接与 MP4 尺寸证据；记录实际 P1 构建兼容结果 | 区分当前行为/目标修正；确定 §7 协议门槛及有限 cap 的 MP4 判定，不操作真实房间 |
| P2-01 投票/锁/队列反馈 | `service/VideoPlayerService.java` 的统一 evaluateVotes、setLock/setAllLocks、top/remove；私有互动 DTO；`MusicSocketController`、`WebSocketAuthInterceptor`、`RoomPlaybackCoordinator` 私发；`PlayerControl.vue`、`QueueItem.vue`、`QueueList.vue`、`stores/player.js` | skip lock 所有评估入口生效；0 秒/票态/触屏/pending 可验收；MUSIC 算法不被顺改 |
| P2-02 历史产品闭环 | `VideoApiController`、VIDEO 历史 DTO/revision 与 mailbox 只读边界；新增 `api/video.js`、历史 composable，`QueueList.vue` 页签/分页 | 只元数据、快照分页隔离、普通 enqueue 重加、双历史及恢复规则不变 |
| P2-03 全局政策 | `AppProperties`、`AdminConfigUpdateRequest`、`SystemConfigSnapshot`、`SettingsSnapshot`/`QueuePersistenceService` 全局字段排除、`SystemConfigService`/`SystemConfigController`、`PlayerState.AppConfigSummary`；新增 `VideoSourcePolicy`；`BilibiliVideoApiService`、`Mp4VideoSource`、`VideoPlayerService` | 原子配置及旧数据默认、源过滤/fence、当前时长续期例外、同实例等待截止不变 |
| P2-04 收藏预览与批次 | 新增 `BilibiliVideoFavoritesService`、链接 parser、preview registry、typed DTO、`VideoImportRegistry`；扩展 `VideoApiController`/`MusicSocketController`/coordinator 专属导入准入、allowlists 和 completion tickets | 20 条分页、100/45 秒预算、统一 reservation、单次 commit、重连幂等及取消终态 |
| P2-05 前端完整接线 | 新增 `useVideoFavoriteImport.js`、扩展 `api/video.js`/`constants/api.js`/`socketHandler.js`/`stores/player.js`/`cleanup.js`；`SearchModal.vue` VIDEO 新流程，`RoomManager.vue` 枚举控件，`systemConfig.js`/`SettingsModal.vue` 政策提示，`AdminDashboard.vue` 意图/文案 | 成功不早报、失败项重试、旧页兼容门槛落实、切房切模式清理、手机入口可用 |
| P2-06 验收和交接 | 增量测试、前端生产构建、隔离后端 package、独立 P2 验证文档；再更新相关知识导航/入口/测试元数据 | 记录实际检查结果及真实设备缺项；代码变化验证后才显式维护索引、本地知识 Git |

代码路径未带前缀时，后端相对 `src/main/java/org/thornex/musicparty/`，后端测试相对 `src/test/java/org/thornex/musicparty/`；前端相对 `music-party-web/src/`。新增类名是建议工程边界，允许实现时按现有风格拆分，不允许取消上述职责/验收约束。

不要求本设计阶段运行 package/npm test、重建索引或更新源码知识审阅基线。实施时使用独立测试/构建输出，不覆盖既有试用包、运行目录和用户数据；不自动启动/重启当前试用服务。知识库仅本地独立 Git，业务提交不包含知识页、导航脚本、AGENTS/CLAUDE 或本机状态。

## 9. 可操作验收用例

本表是**后续实施的验收要求**，不是本次测试结果。用隔离数据与可控 clock/provider fixture 做自动测试，用独立浏览器环境做交互验收；故障注入不在现有房间执行。

| 编号 | 操作/条件 | 预期 |
|---|---|---|
| V-01 | A 点播，B/C 合格，游客 D；B 投/撤票，B 同身份双连接；C 离开 | 分母仅 B/C，token 一票；资格变化正确，D 不控制；达到配置阈值才切 |
| V-02 | 媒体位置门槛前后、1.5×、seek 前后、0 秒设置、暂停/等待 | 服务端与 UI 一致采用媒体时间；0 不变 15；暂停不推进，合法阈值切换保留原意图 |
| V-03 | 已有票但未过线时锁 SKIP，再让成员离开/续期/切回 VIDEO | 自动评估不切；解锁按最新资格/时间重评；明确管理 HTTP 强制切换仍可用 |
| V-04 | 锁暂停/模式，普通成员与 STOMP manager 操作，伪造 HTTP manager | 普通按钮受锁，未验证许可拒绝；倍速/切 P/全局配置角色矩阵准确 |
| Q-01 | 顺序/随机下同条置顶两次、GLOBAL_TOP、失效 queueId、解析 pending | 对应优先级准确，无变化可解释；pending 不打断源，删除取消旧结果 |
| Q-02 | A 三条/B 一条，公平随机开/关，全局置顶，在线候选为空 | 按成员轮替而非全队列等概率；GLOBAL_TOP 不推游标；在线优先回退明确 |
| Q-03 | 相同 BV 不同 P、queue/pending/reservation 竞争，解析失败后删除/clear | 独立 P 去重、配额不提前释放、无丢项/重复/复活，其他模式队列不受影响 |
| H-01 | 自然结束/跳过/单条循环、重复 P、historySize=0/降低 | 只按现有自然结束规则记录，canonical 去重排序/裁剪；不虚构日期/点播人 |
| H-02 | 打开历史、翻页间新增记录、切房/模式后迟到 GET、点击重加 | revision 变更回第一页，旧响应丢弃；重新可信准入，不直接换片、不附源 |
| H-03 | 空队列有历史，history pending 失败，保存/恢复 | 历史随机回放仍有，失败不自动跳；不占用户队列、不持久化成用户项；重启暂停 |
| F-01 | 两种允许链接、重复 fid、伪装主机、短链、CDN、空/私有/失效收藏 | 白名单准确；错误可区分，不 fetch 用户 URL，不将错误当空收藏 |
| F-02 | 第 20/21 条、失效混入、500 条边界、上游 hasMore、未加载全选 | 页码/顺序不错，失效仍可见；全选仅已加载；预算/过期提示准确 |
| F-03 | 默认 P1 超长而 P2 合格、未知时长、选择 P2、客户端伪造 cid/duration | 根据最新可信 P 准入，结果 canonical 为所选 P；不批量加其他 P |
| F-04 | 5 条中 2 成功、1 重复、1 超时长、1 临时失败 | 5 条都有终态；成功 queueId/顺序正确；只失败项可新批重试，不复加成功 |
| F-05 | 两批及单条并发、quota 降低、上限 100/配置更小、45 秒截止 | quota 不超额、reservations/tickets 释放；预算超限不截断；终态数量守恒 |
| F-06 | 接受/commit 后 ACK 丢失，同浏览器同成员重连查询并重发原 requestId | 返回原 operationId/结果，不再入队；异参冲突、不同身份拒绝；旧 ACCEPTED 不覆盖终态 |
| F-07 | 网络未完成时切模式、clear/清用户/离线、断线、Cookie 变更、close | 新链接批次取消、迟到不复活；若 commit 先完成只查原成功；普通单条原模式准入语义继续回归 |
| F-08 | 结果/预览到期、registry 满、服务器重启、关闭面板 | 过期/忙/新 epoch 明确拒绝，不能自动整批重试；关闭面板保留运行批次，查询只元数据 |
| C-01 | ROOT 批量写合法/非法 cap，Owner 写；DB 写失败；旧 system_config | ROOT only、整批保存原子性、无内存假成功；缺字段 UNLIMITED、房间 override 无效 |
| C-02 | 横/竖同尺寸视频、未知尺寸 DASH/MP4、多 codec/帧率与音轨 | cap 按短边；有限 cap 未知拒绝；不按 qn 大小；音轨完整，回退不能绕 cap |
| C-03 | A 偏好高档/B 自动，降低 cap 时暂停或正在等待，旧源回调晚到 | 各端独立回退、偏好不改、同 playbackId/进度/倍速/意图；新 sourceRevision；等待原截止、排除仍在 |
| C-04 | cap 后无合规视频源，再提高 cap/旧回调/心跳；旧 P1 已缓冲 DASH/MP4；无实例 pending | 所有 wire URL 清空、revision 推进并停止旧媒体；不自动 next/超限播放，不发等待超时；有实例/无实例恢复目标校验正确，显式 recover 前不自行起播 |
| C-05 | 当前长片播中降低时长，队列长片/pending/历史重加/切 P/单条循环 | 当前实例续期继续；新实例按新限额；队列不删、pending可处理、不自动跳失败 |
| P-01 | 导入/历史/私有票态/队列先后乱序、旧连接结果、protocol2/旧P1页 | epoch/版本/代际屏障有效；能力缺失不伪装成功；§7 兼容门槛决定是否升级协议 |
| P-02 | 在等待中 next/投票过线/切 P，续期/换画质/置顶/历史查询 | 前者清旧实例，旧 READY 无效；后者不重置实例排除/截止；2秒/15秒及顶部实名提示不变 |
| P-03 | 切 MUSIC↔VIDEO、播放/seek/FM/DJ/直播/管理清理/聊天、保存失败 | 音乐原行为、双现场隔离与严格模式事务回归；不引入视频媒体请求到应用代理/缓存/FFmpeg |

测试入口：现有 `VideoPlayerServiceTest`、`VideoWaitTrackerTest`、`BilibiliVideoApiServiceTest`、`MusicQueuePendingTest`、`MusicQueueAuthorizationTest`、`RoomPlaybackCoordinatorTest`、`QueuePersistenceProtocolTest`、`SystemConfigIntegrationTest`；前端 `playback-store.test.js`、`video-engine.test.js`、`video-manifest.test.js`、`video-cdn.test.js`、`playlist-filter.test.js`、`systemConfig.test.js`。新增导入 parser/provider/registry、历史分页/interaction 和前端导入交互测试，名称按实现确定。现有音乐测试不能替代 VIDEO 的锁/公平/投票组合验证。

浏览器交互至少检查两个独立观看身份、桌面及 390×844 窄屏：队列/历史页签、触屏操作、P 选择、部分失败面板、重连结果、ROOT/Owner 入口、各端画质独立与限额提示。真实设备用例在可用环境再执行，窄屏截图不是手机验收。

## 10. 评审结论、待核实项与验收债务

已确认产品决定：不点赞、只导入提交的 B站收藏夹链接；其余沿 P1 已确认规则。默认工程决定：保留既有投票媒体时间门槛/历史/公平算法；新批次有界、终态可查；cap 默认不限制、按短边分辨率、只 ROOT 修改；当前实例时长准入不被降低上限误伤。这些选择可在评审中调整，目前无需逐项请求用户确认。

实施开始前仍须核实（有明确门槛，不冒充完成）：

- 收藏夹两种输入格式的实际可用 metadata 响应、资源类型/失效/私有错误及 hasMore 证据；不读取运行凭据来完成设计。
- MP4 有限 cap 所需可信尺寸/保守档位证据；若不能得到，明确禁用未知回退并实测影响，不能放宽来源或下载探测媒体。
- 实际旧 P1 protocol3 页的 source 清空/ERROR 行为；通过则保持增量协议，否则成套升级到 protocol4，并更新此文档的最终契约。
- 上述预算（100条/45秒/预览500条）在 fixture 与独立试用环境的可用性；只能有界调整工程预算，不改 P1 的 2 秒/15 秒等待规则。

P1 验证记录的后端 **263/263**、前端 **192/192** 是 2026-10-06 的历史交接基线，本轮未重跑，不能代替当前源码或 P2 验收。仍待补：真实 HTTPS、不同网络、手机/iOS 编解码及自动播放、扬声器连续听感、真实弱网多端超时提示，以及有合适时长上限/1080P 源的连续 2.5 小时自然续期。用户此前安排的这些条件继续保留；桌面本地、模拟状态和受控过期不算这些项目已通过。

P2 完成标准是增量业务实现、上述对应回归与交互证据、真实验收的已过/未过清单和独立验证文档齐备。仅本规格交付不代表 P2 已实现，也不触发部署、推送、业务提交或 PR。
