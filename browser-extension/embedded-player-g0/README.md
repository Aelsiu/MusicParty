# MirthParty 内嵌解析器

桌面 Chrome 与 Edge 共用同一扩展包，视频使用网页统一控制条，扩展不显示原生控制条或常驻提示，横屏和竖屏都填充宿主容器且不产生滚动条。安装和使用步骤见 [CHROME-EDGE-使用说明.md](CHROME-EDGE-使用说明.md)。

构建：`node browser-extension/embedded-player-g0/build.mjs`。版本只读取项目根目录 `VERSION`，提交号来自当前 Git HEAD；目录和同级 ZIP 均命名为 `build/issue-26101101/MirthParty-parse-版本-提交短号`（ZIP 加 `.zip`）。例如 `MirthParty-parse-2.1.0-2475358`。浏览器扩展列表显示名称为“MirthParty 内嵌解析器”。`manifest.version` 使用发布版本的三段数字，`version_name` 与生成的 `build-metadata.js` 保留完整 `版本-短号`，包括预发布标识；数字段需符合浏览器的 0..65535 限制且不能全为零。

构建结束前重新校验 VERSION 和 HEAD，变化时拒绝发布。重复构建同名产物会将上一份目录和 ZIP 保留为 `.previous-随机号`，新包由独立暂存目录生成，不混入旧文件；旧 `embedded-player-g0` 目录也会保留。提交号标记构建基线，不代表工作树已提交。dash.js 来自已安装依赖并保留许可证，没有远程脚本。Windows 构建使用系统已有的 `tar` 打 ZIP，无新增依赖。

桌面 Chrome / Edge 弹窗使用项目橙色音乐图标、统一 `v版本-短号` 字幕和同排地址/开关。首次网站地址为空，之后记忆最后配置的地址；只接受 HTTP(S)，规范化为协议、主机和端口。启用时由用户开关手势直接请求该网站的可选访问权限，再由 worker 核对权限并注册顶层隔离桥；实际确认后才显示已启用并锁定地址。停用等待旧 iframe、注册脚本和规则清理确认，再解锁地址。权限撤销或重新打开弹窗都会回读实际状态；弹窗不请求网页内容。

网站访问权限使用可选 `http://*/*` / `https://*/*` 声明并按用户配置地址请求；必需 host 权限仍只含原 HTTPS CDN。`scripting` 用于动态注册隔离桥，不再静态注入固定网址。worker 独立强制精确协议/主机/端口和配置代际，顶层 documentId / tab / 一次性 token 与私有 MessageChannel 挑战绑定实际 iframe；公开 renderer URL 本身不授权。规则仅匹配自身扩展发起、已授权 tab、原有 HTTPS CDN、GET media / xmlhttprequest。renderer 直接请求和消费 CDN 媒体，网页只收有界状态，没有媒体代理。READY 只表示握手，真实可播状态才授予观看资格。

导航、权限撤销和停用会清理呈现。配套网页通过 `features.pausedFrame=1` 协商暂停末帧能力：全局暂停的实际帧在 renderer 本地 Canvas 显示；自然 idle 或恢复释放后立即清源、清自身认证规则并废弃应用授权，只保留不可复活的静态 iframe。恢复创建新授权/iframe，实际对齐帧证明通过后才交接；网页不接收图像字节，不保活 worker。STOP/DISCONNECT/CLEAR_PRESENTATION 表示销毁，RESET_SOURCE/RESET_CONNECTION 表示同呈现身份的减权恢复，真正撤权始终优先。旧 LOAD/SYNC 不提供呈现身份时继续按原行为清理 iframe。规则 ID 独立分配并在 session 中单调持久化，避免旧 renderer 清理误删新规则。

末帧正式关卡：先构建到本轮隔离目录，运行 `node browser-extension/embedded-player-g0/tests/paused-frame.mjs edge|chrome mp4|public-dash [long|black|revoke]`；long 观察后台60秒及前台120/300秒，black 使用生成的合法黑色MP4，revoke 在自然idle后通过原生管理页撤销访问并核验清帧/BLOCKED。仅测试副本预授精确fixture网站权限，生产manifest仍为可选权限。公开DASH使用匿名免登录来源，未读取用户凭据；本轮证据与早期原理副本分列在 `build/issue-26101213-implementation/` 与 `build/issue-26101213-spec/`。这些关卡不能替代实际用户房间和设备验收。

测试：`node --test browser-extension/embedded-player-g0/tests/*.test.mjs` 验证元数据、产物和授权策略；`renderer-layout.mjs`、`browser-g0.mjs`、`idle-worker.mjs`、`real-cdn.mjs`、`rooms-edge.mjs` 在运行前按当前 VERSION / HEAD 定位并校验扩展产物。使用独立 profile，不附着日常浏览器。`MIRTH_PLAYWRIGHT_RUNTIME` 指向已安装 Playwright 包，`MIRTH_G0_BROWSER=chrome|edge` 选择浏览器。正式版 Chrome 自 137 起移除命令行 `--load-extension`；Chrome 测试先在扩展管理页打开 Developer mode，再使用仅限测试的 CDP `Extensions.loadUnpacked`。普通用户按安装说明选择解压目录，不需要调试参数。参考 [Chrome 官方安装教程](https://developer.chrome.com/docs/extensions/get-started/tutorial/hello-world) 与 [Chrome 官方命令行变更说明](https://groups.google.com/a/chromium.org/g/chromium-extensions/c/1-g8EFx2BBY/m/S0ET5wPjCAAJ)。

合成隔离测试只对本地 fixture 忽略自签证书和本地网络检查；真实 CDN 与房间测试保留 TLS / 本地网络检查。日志不保存媒体、签名 URL、原 API 响应或凭据。Chrome 155.0.8059.40 与 Edge 155.0.4283.45 布局验证通过；Chrome 真实匿名 EP94950 CDN 206、双轨解码、父页面点击启动和 seek 30 秒后继续推进通过。浏览器证据保存在 `build/issue-26101101`；真实人工声画、自然签名到期及实际云端多设备网络仍需验收。
