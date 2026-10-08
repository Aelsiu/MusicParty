# P2 收藏夹与协调器实现说明

日期：2026-10-08。以下是隔离实现/fixture 验证记录，不是实际 B站收藏夹或真实设备验收。

新增视频专属 `VideoFavorites` typed 元数据、链接白名单 parser、固定官方 metadata provider、房间邮箱拥有的预览和操作 registry、并发受限的 descriptor resolver。收藏链接绝不作为 HTTP 请求地址；provider 固定请求 `https://api.bilibili.com/x/v3/fav/resource/list`，仅带经校验的收藏 ID、页码和 20 条页大小，复用现有 Cookie 设施，不下载媒体或获取播放源。

预览按批准规格有效 10 分钟，同验证浏览器每房间一个，房间最多 16 个；最多 25 页/500 条。原始失效和非视频项保留可见，`hasMore` 来自上游，不由过滤条数猜测。已缓存页面不可变，接受批次时复制所选 `itemId/bvid/page`，随后预览替换/过期不影响已经接受的任务。

批次由 coordinator 在 mailbox 同时预留 registry 条目、completion ticket 和统一队列 quota。有效上限为 `min(maxPlaylistImportSize,100)`；仅预留有 quota 的前若干选择，其余保持逐项 QUOTA_EXCEEDED 结果。网络阶段最多 3 个并行 metadata 请求，单请求 20 秒，总窗口 45 秒。总窗口到期保留完成的结果，将未完成项标为可重试临时失败。最终回到原 mailbox 一次有序提交，复查成员身份/模式代次/来源开关和最新时长/quota/重复；释放本批 reservation 后入队，公共队列版本经一次 flush 推进。

active 与未过期 terminal 合计最多 32 项，active 最多 8 项、同浏览器同命名成员一次一项。terminal 原位保留 10 分钟。账本 key 绑定运行期 epoch、房间、验证浏览器、命名成员 token 和原 requestId；参数相同的重连重发返回原操作/终态，异参返回冲突。查询不重新执行且允许读取原模式代次终态。切模式、Cookie/来源关闭、清队列/用户、关闭房间及发起 session 断线取消未提交批次并释放 quota/ticket；其他标签页断线不取消。既有单条 enqueue 继续原 engine 路径。

coordinator 同时接入历史 mailbox 分页、私有本人票态、recover 路由及 STOMP allowlist。历史只返回元数据与运行期 revision。私有互动在公共状态发布之后发送，带 stateRevision/interactionRevision；当前连接 resync 返回本人资格/票态。

隔离输出 `build/p2-favorites-target` 的定向运行通过 23/23：既有 RoomPlaybackCoordinatorTest 9，VideoFavoritesRegistryTest 4，VideoImportCoordinatorTest 5，BilibiliVideoFavoritesServiceTest 3，VideoFavoriteMetadataResolverTest 2。运行采用本机缓存 Maven、JDK 21、启动时 Byte Buddy agent 与 `-DforkCount=0`。测试覆盖 ACK 丢失后同 key 重连、冲突、发起 session/其他标签页取消、模式切换与单条入口分离、票据回滚、账本容量/到期、固定预算/并发与逐项安全失败。其他 P2 完整回归结果见独立总验证文档。

**尚无真实收藏响应证据。** 使用 web 工具访问固定官方无凭据测试 ID 地址失败（工具报告 inaccessible）；随后 PowerShell 对同一固定地址发起无 Cookie 请求得到 HttpRequestException，未收到 HTTP 状态/响应。所有 provider 响应测试均为显式合成 fixture，不能据此声称已核实真实收藏私有/失效错误、has_more 或资源类型。实际支持的两种分享格式、真实空/私有/失效收藏、20/21 条边界和 500 条实际预算仍需具备外网与合法收藏条件后补验；没有读取现有运行凭据来补证据。
