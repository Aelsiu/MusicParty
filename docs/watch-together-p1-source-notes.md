# P1 来源解析工程记录

P2 追加（2026-10-08）：全局有限分辨率上限按表示的编码尺寸短边过滤，DASH 保留合规视频及完整音轨；同时清理不合规 MP4、`source.url` 和备源别名，重算剩余 URL 的期限。`Mp4VideoSource.width/height` 可空，仅接受该返回段的正整数尺寸；原片尺寸、请求 qn、quality 标签不是降档流尺寸证据。现有 P1 脱敏记录只确认 MP4 的格式/quality/完整性，没有足以确立普通 MP4 档位尺寸映射的证据；P2 不虚构映射，也未读取运行 Cookie 或下载媒体作探测。因此缺少尺寸的 MP4 在有限 cap 下禁用，在 UNLIMITED 默认下保持可用。此处新增段尺寸 fixture 只验证解析边界，不宣称实际官方响应必然提供这些字段；真实有限 cap 下 MP4 可用性仍待独立环境验证。

P2 的 `resolveCurrentInstanceSource` 仅供服务端成功准入的当前实例续期，保留可信 P 身份/时长一致性、来源格式和 cap 校验；当前实例不因 ROOT 后来降低时长上限而在续期时截断。新实例、重新入队、切 P 仍由普通 `resolveSource` 按最新时长准入。

日期：2026-10-05。本文记录媒体 API 与 DTO 的实际实现、探针范围，不把 API 解析成功算作浏览器播放验收。

## 可信分 P

输入保留大小写并归一化为 `BV…:pN`；普通 HTTPS B站链接的单个 `?p=N` 可选择对应 P。重复 p、零/负数、前导零、不存在的 P、AV、短链接和 CDN 输入均拒绝。P 编号与元数据数量有界（最多 1000）。全部 P 的 cid、page、part 和 duration 来自 `/x/web-interface/view`，要求编号连续、cid 唯一、时长为正。

`getVideoDescriptor` 用于入队 / 解析，会检查所选 P 的独立时长上限。`getVideoMetadata` 用于选择界面，允许默认 P 超长但仍返回全部可信 `pages`，每项含 `withinDurationLimit`，后续入队与 source 解析仍严格检查。聚合 duration / cid 不作为选定 P 的依据。

## 临时来源契约

`ResolvedVideoSource` 保留原字段，并添加 `dash` 与 `mp4`：

- `dash` 为 `videoTracks`、`audioTracks`、可信 `durationMs` 与秒单位 `minBufferTime`。
- 每个 `MediaTrackSource` 含稳定 `qualityId`（视频 `q80` 等、音频 `audio-30280`）、实际 `qualityLabel`、id、mimeType、codecs、bandwidth、尺寸 / 帧率、初始化 / 索引字节范围、主备 HTTPS 地址及期限。相同画质的不同 codec 使用同一画质标识；实际轨道仍分开。
- `mp4` 为完整单段且已复核时长的复用音视频回退。`source.url` 仅为这个真正 MP4 的地址；没有回退时为 null，绝不把 DASH 视频单轨包装成有声媒体。
- 后端将有效 DASH 映射成 `format=dash`，不依赖 B站外层 format 标签。实际 probe 的外层是 `flv`，但内部有完整 DASH。

请求 DASH 使用 `fnval=4048&qn=80&fourk=1`，随后独立请求 `fnval=1&platform=html5&qn=32` 取得 MP4 回退。不可用的 DASH 可以转 MP4；可用的 DASH 不因可选回退失败而丢弃。没有代理、媒体缓存、媒体下载或 FFmpeg 路径。Cookie 不进入 DTO 或日志，DTO 的 `toString` 隐去临时地址。

检查音视频两轨必需存在且 codec / MIME 在已知 MP4 家族内，支持 AVC / HEVC / AV1 视频和 AAC 音频；客户端仍需要 MSE / 解码能力验证。校验初始化和索引范围、两种字段命名（camel / snake）、总时长与所选 P 的一致性，拒绝试播 / DRM / 多段 MP4。实际可选画质来自返回的 representations，不把 accept_quality 或请求 qn 当成已可播放质量。

CDN 限定 HTTPS `*.bilivideo.com` 的默认 / 443 端口与 `*.mcdn.bilivideo.cn` 的默认 / 443 / 8082 端口；禁止用户信息、片段、混淆后缀、任意 cn 主机和其他端口。后者是官方 `/x/player/playurl` 真实返回的主机与端口（见下节），不是仅据第三方清单放行。标准端口不代表可直连：10-06 追加探针确认一些 443 备源有防盗链限制，不能据端口优先取代实际可用的主源。

已知 expiry 取所有提供音视频 representations 和备用 URL 的 `deadline` 最早值，加上 MP4 回退期限；初次解析拒绝已到期或有效期不超过 5 秒的 URL。没有 deadline 时不虚构固定两小时期限。已知与未知混合时也必须使用有界软新鲜期，不能因存在一个 known deadline 而忽略另一轨的未知期限；续期调度参数由播放引擎记录。原始源仅在内存 / 授权快照存在，不能持久化。

## 真实来源 API 探针

只读原隔离数据库内已配置的 Cookie，在内存中请求官方元数据与来源 API；不更改房间和播放，不输出 Cookie、响应正文或签名 URL，不保存临时地址。安全脚本 / 脱敏报告在忽略目录 `.video-party-trial/verification/p1-source-contract-probe.py` 与 `.json`。

`BV1jKHE6qEBY:p1` 的实际结果：view 和 playurl code 均为 0；可信 P 时长 62 秒、流时长 61333 毫秒，DASH duration 为 62 秒、minBufferTime 为 1.5 秒。来源包含 q116 / q80 / q64 / q32 / q16，每档分别有 AVC / HEVC / AV1 表示，共 15 视频轨；3 个 AAC 音轨。全部轨道有初始化 / 索引范围；例如 AVC q80 为 `0-934` / `935-1130`，AAC 为 `0-836` / `837-1032`。主机包含实际 HTTPS `*.mcdn.bilivideo.cn:8082` 及标准端口 `*.bilivideo.com` 备源。观察时各签名 deadline 剩余约 7184 秒，仅作为此次实测，不保证其他源固定有效两小时。

独立 MP4 请求返回 format=mp4、quality=16、一个完整段、标准 bilivideo.com CDN。此 API 证据不代表 CORS / Range、浏览器双轨解码、连续声音、自然长时失效、手机或 HTTPS 跨网络验收通过；这些由 P1 本地整体验证补充。

### 10-06 直连 Range / 防盗链追加诊断

在 P1 浏览器实际 MP4 连续音频解码成功、DASH 请求 403 后，运行独立只读小 Range 矩阵；不操作房间，不建立代理、缓存或服务器播放路径，仅读 65 字节并丢弃。官方 Cookie 只用于 API 请求且仅在内存中，CDN 请求不携带 Cookie。脱敏结果为 `.video-party-trial/verification/p1-dash-direct-probe.json`，不含签名地址、Cookie 或媒体内容。

- 官方返回的视频 mcdn HTTPS 8082 主源，在 Chrome 120 / Edge 144 UA、有 / 无 localhost Origin、有 / 无 localhost Referer 的独立请求中均返回 206；Content-Range 正确，`Access-Control-Allow-Origin: *`。
- 同轨标准 443 bilivideo.com 备源在无 Referer或 localhost Referer下返回 403；仅用于诊断的 B站 Referer下返回 206，说明存在防盗链限制。浏览器的最终方案不能伪造 B站 Referer，也不能把 CORS 头存在等同于媒体可播放。
- 最高码率 AAC 30280 本次主备均为上述 443 源，因此取最高码率并优先 443 会导致双轨失败。官方返回的 AAC 30232 / 30216 有 mcdn 8082 地址，它们在真实 localhost Origin / Referer与 Edge UA 下均返回 206、正确 Range 和 `ACAO=*`。可用音轨必须结合实际直接请求能力，不能只按 bandwidth 排序。
- `fnval=4048` / `16` 默认、platform=pc、force_host=2、qn=64 / 16 均仍返回 DASH；增加 platform=html5 则返回 `mp4720` 单段且没有 DASH。这些参数不能作为获得跨来源 DASH 的保证。

该独立小 Range 证据定位来源选择问题，仍不等于浏览器完整 DASH 双轨解码 / 连续声音通过。客户端应只在可信集合中选择可直接请求的轨道与地址，失败时按既有规则回退 MP4，不伪造 Referer或新增代理。

## 针对性回归

`BilibiliVideoApiServiceTest` 增加 / 更新分 P 选择、独立时长限制、metadata 默认页超长、DASH 双轨、实际质量、camel/snake 字段、丢音轨 / 错 range / 错 duration、主备期限与即将过期拒绝、DASH+MP4 请求参数、可选回退失败以及 CDN 端口边界测试。协调器统一运行的首轮完整 package 已确认该测试类 **21 项通过，失败 / 错误 / 跳过均为 0**，报告位于隔离 `.video-party-trial/p1-target/surefire-reports`。后续播放引擎与前端修正后的总回归结果以本轮总验证记录为准。
