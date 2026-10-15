# P1 前端工程与验证记录

日期：2026-10-05；CDN 修复更新：2026-10-06。本文件记录客户端工程选择；真实媒体、端到端与长时验收以独立验证报告为准。

## 播放器与来源

- 沿用 P0 单 `<video>`、无原生控制、playsinline 和房间控制，不增加一套 ArtPlayer 控制面板。安装 `dashjs 5.2.1`，使用官方现代 ESM 包，按需加载。
- `nowPlaying.videoSource` 的可信轨道经 `MediaSource.isTypeSupported(mimeType + codecs)` 筛选。优先 AVC 视频 / AAC 音频；自动档保留同 codec 家族的 ABR；手动档仅加载对应画质。不兼容或不存在的手动档向可用较低档回退，长期偏好不变。
- 静态 MPD 使用独立 video / audio AdaptationSet、Representation codec / bandwidth、可信 duration、SegmentBase indexRange 与 Initialization range；主备 BaseURL 只来自服务端来源集合。保留服务端可信主地址在前、备用地址在后；端口不代表可直连性。地址仅在运行期内存和 Blob MPD 使用，无本应用媒体代理、缓存或转码。
- DASH 双轨不可用或加载错误时，使用服务端明确的有声 MP4 回退；不把无音频的视频单轨当作 MP4 播放。若服务端无可用回退，明确显示错误并请求合并的来源恢复。
- 源版本、播放实例、连接代次及本端画质偏好按标量监听。相同心跳替换对象不重载。每次换源增加本端 sourceGeneration；DASH Promise、事件和旧 play Promise 均隔离，卸载 reset + revokeObjectURL。同实例合格端 MP4 换源在暂停加载时仍保持 BUFFERING 观察；可恢复网络错误只有真实缺数据才进入等待，编码错误退出资格。P0 首次对齐、明确小 seek、500ms 常规漂移阈值和 stalled 下载事件排除保留。
- `preload=auto`，dash.js `scheduleWhilePaused=true`，默认媒体缓冲 12 秒 / 顶档 20 秒 / 顶档长片 30 秒，允许房间等待时继续预缓冲；DASH 内部日志级别 0 防止签名地址进入日志。

参考：[dash.js 官方 Setup](https://dashif.org/dash.js/pages/quickstart/setup.html)、[ABR Settings](https://dashif.org/dash.js/pages/usage/abr/settings.html)、[Player Events](https://dashif.org/dash.js/pages/usage/player-events.html)，并核对安装包的 `index.d.ts`。

## 画质、分 P 和房间控制

- `mp_video_quality_preference` 只保存 `{version:1,mode:'auto'}` 或 `{version:1,mode:'manual',qualityId:'q80'}`。损坏 / 无效版本 / 不可用存储回退自动，不保存 codec / 地址 / 代次。设置在“歌词提前显示”下面，独立展示实际画质及回退方式。
- 没有兼容 DASH 时不虚构档位；保留最近视频可信且本机兼容的画质标签，音乐模式可保存该偏好，并标明“上次视频可用”。新视频仍重新按可信响应和能力校验。
- 搜索结果或裸 BV 点击后，通过许可上下文保护的 `GET /api/video/metadata/{bvid}` 获取全部 P，明确选择单个 P 入队；`?p=N` / `BV:pN` 输入保留所选 P。超时长页禁用；入队与当前 P 切换分开。当前 P 切换仅向房间发送 `page`，新实例及 0 起点由后端发布。
- 房间倍速沿用指定七档，前端控件仅对管理者开放；本端 media playbackRate、服务端快照延迟补偿和本地时钟推进使用同一倍速。MUSIC 时钟仍为 1 倍。
- 连接、HTTP 管理动作和快照屏障统一 protocol 3；protocol 2 快照不能建立同步屏障。

## 等待与提示

- 每秒独立发送 `/app/video/watch/status`，不依赖媒体事件；携带当前上下文、sourceGeneration、单调 sequence、前台 / 本地播放许可、等待版本和媒体位置。游客可观察报告，不能因此控制房间。观看身份由服务端签名 HttpOnly Cookie / WS handshake 验证，客户端不生成观看 token；同浏览器多标签保守共享当前播放实例的超时排除记录，连接世代与本端来源代次分别校验。
- 本端自动播放拒绝 / 尚未解锁、后台及编码错误不阻塞房间。正常同实例换源保留已观看资格。等待的程序暂停保留本地播放许可。
- 等待下 readiness / playing / 200ms 同步任务禁止自行播放；展示冻结锚点，并继续下载。READY 必须未 seeking / 无 media error、readyState >= 3、位置距冻结点 <= 350ms、HTML combined buffered 覆盖目标水位，DASH 音频 / 视频指标均达到水位。
- 恢复水位为 `1.5 × playbackRate` 媒体秒，接近片尾只要求实际剩余时长。源变更 / seek / 倍速 / waitRevision 后重新判断，报告从当前版本读取。
- 底部暂停按钮依据 playIntent 区分手动暂停和有效等待。取消 / 恢复 / 2 秒触发 / 15 秒截止 / 超时身份排除由后端统一执行。
- `VIDEO_WAIT_TIMEOUT` 继续走现有 socketHandler → useToast → ToastNotification；eventId 去重集合最多 256 项，匹配 epoch / modeEpoch / playbackId，即便屏障已清除仍可展示。实名消息允许多行，宽度限制为视口减 24px。

## 本地自动化

当前前端 `npm test` **192 / 192** 通过，包括原 P0 回归及新增 MPD 双轨 / range / XML、兼容档位 / 偏好安全、冻结点双轨水位 / 片尾、等待回调不得播放、后台 / 自动播放观察、旧 play Promise / DASH callback 隔离、protocol 3、游客观察与管理控制和公共 / 私人超时事件去重。

生产构建使用 `npm run build -- --outDir ../.video-party-trial/p1-web-dist --emptyOutDir`，仅清空 P1 专用输出目录；没有写入仍由 18081 使用的 `music-party-web/dist`。受限环境 Vite realpath EPERM 后，经自动审批在隔离外成功构建。

这些单元测试和构建结果不代替真实 DASH 连续声音、Range / CORS 实播、双端 / 不同网络、HTTPS、手机或 1080p 连续 2.5 小时验收。


## 2026-10-06 真实 CDN 失败定位与修复

第一版实际浏览器中，DASH 各轨 SIDX Range 请求得到 403，自动回退 MP4 后音频持续解码；这不是 DASH 双轨验收通过。定位为客户端把标准 443 备用 CDN 排到可信主 CDN 前，以及只按带宽选择最高 AAC。来源矩阵表明当前 30280 主备不可本地直连，而 30232 / 30216 有可信且可 CORS 读取的 mcdn 8082 地址；视频主 mcdn 地址也返回 206。

- 恢复可信来源原始主备顺序，保持签名 URL 原文。没有修改签名参数，没有伪造 Origin / Referer。
- 音轨在创建 MPD 前做有限客户端直连预检：`Range: bytes=0-63`、`mode=cors`、`credentials=omit`、`cache=no-store`；只接受可读 206。所有响应立即 cancel body，不读 / 保存媒体 bytes。按兼容音轨、其原可信地址依次尝试，单次最多 1.2 秒、总窗口 6 秒、最多 6 音轨且每轨 4 地址。源变更 / 卸载通过 AbortController 取消，迟到结果不能提交。选中的是完整的实际音轨对象，codec、初始化 / SIDX ranges 与其 URL 一起使用。
- dash.js 5.2.1 的 SegmentBaseLoader SIDX 下载没有可靠经过 FragmentController 的 BaseURL blacklist 路径。因此接入官方 request / response interceptor：一次请求失败后只在该轨道的原可信地址集合内轮转；保持初始化 / SIDX / segment 的 Range 不变。每轨最多 4 地址，媒体 / 初始化 / index 请求最多 3 次重试；旧来源的拦截器失效。详情参考 [官方 Network Interceptor](https://dashif.org/dash.js/pages/usage/network-interceptor.html)。
- 初次加入预检期保持不合格，已合格同实例换源期间保持真实 BUFFERING 观察；这些本端尝试不改变房间锚点、倍速、播放意图或等待截止。无可直连音轨或 DASH 失败仍明确回退可信有声 MP4。

新增回归覆盖可读 206 选择、不可读 / 非 Range 拒绝、预检总窗口 / 取消、正文不读取、签名 URL 原文、同轨 Range 不变、音视频互不轮转、迟到响应与旧代次，以及 MPD 主备原序。实际双轨浏览器复测由独立验证报告记录。


### 单档 SIDX 失败隔离

实际安装包的 `RepresentationController.updateData` 会遍历当前 AdaptationSet 的全部 Representation 并加载 SIDX，不只加载当前 ABR 所选档。因而某个不可直连的低档也能使全份 DASH 出错。现在创建 MPD 前对当前兼容 codec 家族的视频表示做小 Range 预筛，只纳入能返回可读 206 的表示，不让不可达的 q16 拖累其他已可达档。每个表示保留自身 codec、Initialization / SIDX ranges 和原可信主备 URL。

视频预筛最多 16 个表示、每表示最多 4 地址，并发不超过 3，共享 6 秒总窗口；音频与视频阶段并行，最多同时 4 个请求，整体预筛阶段最长 6 秒。取消源代次会取消全部请求并丢弃迟到结果，正文始终不读取。手动画质从已验证可达的档位中选择保存偏好或回退，用户长期偏好不变；设置只显示本源预筛可用的档位。新增测试覆盖单档失效剔除、各轨 ranges 不变、并发 / 预算 / 候选数上限和并行取消。

另一个实际测试视频的全部音轨本地直连均 403，按预期使用有声 MP4 回退。这证明回退规则可用，不计为 DASH 通过；真实可达双轨视频的复测继续由协调器记录。


### 错误回退时序

dash.js 在 `errHandler.error` 发布 ERROR 后才执行失败请求的 `config.error`，后者使 SIDX / RepresentationController 的 Promise 链完成。在 ERROR 回调里同步 `reset()` 会让剩余 microtask 访问已清空的 representation。现在把 ERROR 的回退 / reset 推迟到下一 macrotask，并绑定本端来源 token；同源重复错误只安排一次，换源 / 卸载取消 timer，过期 timer 不能触碰新播放器。等待下 readiness / playing 仍不能自行播放，排队回退期间也阻止本端自动 play。模拟回归验证错误发布期间 engine 不 reset、timer 后只 reset 一次并进入 MP4，旧来源回调无效。
