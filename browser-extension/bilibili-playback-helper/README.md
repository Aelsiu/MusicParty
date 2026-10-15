# MirthParty 播放辅助 G0 诊断包 0.0.1

此包不是可用的播放辅助扩展。G0 精确 Origin 隔离未通过，生产规则编译器固定拒绝启用；worker 启动、安装和重载只清除自己的会话规则，popup 核对是否已清除。没有 content bridge，不回复 READY，不读取 Cookie，不获取或转发媒体。

`rules.js` 只构造可审阅的候选条件，测试不会向浏览器注册它。条件模型明确显示：同 tabId、同发起主机、不同协议/端口的 iframe 请求仍会命中。顶层文档握手不能增加 DNR 不具备的请求条件；不能通过开启该候选规则宣称满足 SPEC。

诊断 manifest 的 `declarativeNetRequestFeedback` 仅用于后续已解压调试，不作为生产权限。CDN host 权限与唯一配置 Origin 固定随包发布，不允许网页添加站点。没有 cookies、debugger、webRequestBlocking、全网站权限或响应 CORS 修改。

运行测试：`node --test browser-extension/bilibili-playback-helper/tests/*.test.mjs`。

后续真实 G0 验证必须记录 Chrome/Edge 完整版本。先在隔离测试环境以浏览器自身规则匹配诊断检查同主机不同 Origin/iframe、worker 和导航竞态，再检查云端同一有效受限来源无辅助失败、可读 206、声画、seek、连续分片及生命周期清理。任一越界均阻塞正式接入；此包保持关闭，不提供绕过开关。

仅在后续测试需要时，在 Chrome 的 chrome://extensions 或 Edge 的 edge://extensions 打开开发者模式，加载 ZIP 解压后的根目录（manifest.json 所在目录）。本轮未执行安装，也未作真实网络/声画验收。刷新 popup 不等于播放通过；出现 ERR 或规则核对错误时，停用/卸载扩展。更新需 reload 扩展并刷新站点，不上官方商店。

部署 Origin 已于 2026-10-11 在 Edge 打开确认是 http://101.132.17.83/。支持浏览器版本、规则真实范围和正式可行性仍待验证。权限条件参考：[Chrome DNR](https://developer.chrome.com/docs/extensions/reference/api/declarativeNetRequest)。
