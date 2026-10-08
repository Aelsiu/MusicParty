# P1 本地验证记录

日期：2026-10-06。按 [P1 规格 v1](watch-together-p1-implementation-spec.md) 实施，入口与重建步骤见 [P1 本地试用](watch-together-p1-trial.md)。本轮未推送、部署或创建 PR。

## 隔离范围

P1 使用 `.video-party-trial/p1-web-dist`、`p1-target` 与 `p1/MusicParty-video-p1.jar`，服务只监听 `127.0.0.1:18082`。SQLite 来自原试用数据的一致性备份，后续数据独立；原房间播放未操作。原 `music-party-web/dist/index.html`、`target/MusicParty-0.0.1-SNAPSHOT.jar` 和 `target/MusicParty-video-buffer-fix.jar` 的 SHA256 与开工基线一致。对照结果在忽略目录 `verification/p1/protected-integrity.json`。

媒体始终由观看浏览器直接请求可信 B站 CDN；应用未增加媒体代理、缓存或 FFmpeg 播放链路。凭据仍在本地管理机制中，探针和报告不含 Cookie、管理会话、完整签名 URL 或媒体内容。

## 自动回归

最终后端离线 `package` **263 / 263** 测试通过，失败 / 错误 / 跳过均为 0，包含 MUSIC、房间隔离、协议、来源、时钟、等待及续期回归。前端 **192 / 192** 通过，独立生产构建成功，资源为 `index-B9h58sPu.js`。后端输出隔离在 `p1-target/surefire-reports`，编译 / 测试日志在 `verification/p1/package-tests.log`。

独立最终包为 `p1/MusicParty-video-p1.jar`（44,099,080 字节），ZIP 完整性及静态入口匹配通过。已以该包重新启动 18082；与原 P0 的 protocol 2 网页 / 后端配套独立。

## 实际来源与浏览器

官方元数据 / playurl 探针确认可信分 P、DASH A/V 表示、SIDX / 初始化范围、真实画质和 MP4 回退。CDN 小 Range 诊断确认部分 443 备源返回 403，而官方 mcdn HTTPS 8082 主源返回 206 且允许 CORS。音轨和视频表示须结合实际直连能力选择，不能以端口或码率决定可用性。详见 [来源说明](watch-together-p1-source-notes.md)。

浏览器使用本机无头 Microsoft Edge，两个独立上下文及观看身份，完整流程已通过，页面脚本错误为 0。安全报告为 `verification/p1/browser-media.json`，桌面和 390×844 窄屏截图位于同目录；已检查窄屏无横向溢出，分 P 与倍速控件可见。该测试开启静音，音频解码字节增长证明双轨音频持续解码；扬声器实际听感待人工检查。

`BV1jKHE6qEBY:p1` 两端 DASH 在约 12 秒观察窗口分别推进 11.939 / 12.014 秒，音频解码字节分别由 21,196 → 187,742、22,243 → 188,789；期间 seeking / loadedmetadata / waiting / playing 计数均未增加。A 手动 720p、B 自动实际 1080p60，修改 A 偏好不改变 B。设置项实际 DOM 紧接“歌词提前显示”。视频和音轨均由可信 mcdn HTTPS 8082 地址完成 CORS / Range 请求及浏览器解码，不使用防盗链 Referer伪造。

对已合格端换画质的实际 CDN 请求施加延迟，真实播放器状态触发两人屏障；房间冻结在 24.804 秒，放行后两端均定位约 24.809 秒，在原 15 秒截止前恢复且无超时通知。第二轮延迟中手动暂停后，两端保持 PAUSED 且等待已取消；恢复播放及跳转至 15 秒通过，两端分别观察到 15.009 / 15.037 秒。

`BV1uW411f72n:p1` 的 DASH 音轨本次无法在本机 CORS 直连，按明确规则使用原生有声 MP4 回退。约 10 秒窗口内，A 的音频解码字节 28,410 → 208,998，B 为 27,642 → 208,203；在房间 1.5× 下 A 媒体时间 2.084 → 17.100 秒，两端持续推进。失败预检没有虚构可用 DASH 档。

在暂停意图下通过当前分 P 控件切到 `BV1uW411f72n:p2`，两端均完成来源载入，媒体位置为 0；playbackId 已更换且两端一致，PAUSED 意图与 1.5× 保留。旧实例来源与报告不串入新 P。没有隐式把其他 P 批量入队。

## 协议与等待

最终隔离包 live-protocol 脚本全部通过：protocol 2 的 STOMP / HTTP 在获取来源前被拒绝，protocol 3 可连接命名成员与游客；游客观察权限和控制权限独立。管理者 1.5× 的服务器时钟比例实测 1.501；等待冻结、手动暂停取消、等待中续期保留截止、实名单次超时提示、同一已签名观看 Cookie 重连排除，以及旧 modeEpoch / sourceRevision / playbackId 各连续 3.3 秒报告不能建立等待均通过。

服务端单调时钟边界的可控单测覆盖 1.999 / 2 秒与 14.999 / 15 秒；最终实服务从有效缓冲报告到收到屏障约 2,044 毫秒。超时事件在公开截止后约 6 毫秒收到，显示服务端成员名“P1弱网验证B”，同一端同一轮一条通知。客户端开始观察屏障比服务端创建稍晚，观察窗口约 14,991 毫秒，不能把它误当作服务端提前超时。

这组协议测试主动构造 STOMP 状态报告，与浏览器测试中延迟真实 CDN 请求触发的缓冲分别记录。可控时间 / 状态用例不计作自然长时网络验收。协议安全报告为 `verification/p1/live-protocol.json`。

## 待补的真实验收

- 真实 HTTPS、不同网络、手机浏览器与 iOS 的编解码 / 自动播放行为。
- 扬声器连续听感，弱网设备长时间恢复与真实多端超时顶部提示的人工观察。
- 有足够时长上限与可用 1080p 来源的连续 2.5 小时播放、自然到期与续期。当前本机短片及受控续期用例不能替代它。

工程参数和边界见 [前端说明](watch-together-p1-frontend-notes.md)、[来源说明](watch-together-p1-source-notes.md)及[后端说明](watch-together-p1-backend-notes.md)。本地窄屏视口不作为真机验收。
