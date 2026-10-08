# 本地试用版验证记录

验证日期：2026-10-05；工作分支：`video-party`。本次按用户选择交付本地可试用版本，真实 HTTPS、不同网络和手机验收后补。首次交付验证未配置 Cookie；后续音频修复验证使用用户自行在本地管理台配置的房间，探针没有读取 Cookie 内容。

## 本机运行与协议

打包 JAR 内含 Vue 网页。试用脚本使用独立 `.video-party-trial` 数据目录，监听 `127.0.0.1:18080`，未覆盖原有配置和数据库。试用许可 `（见本机独立运行配置）` 仅用于这份本机隔离环境。

在运行中的真实 Spring Boot 服务上，以两个独立 STOMP 客户端和 HTTP 请求验证：

- protocol 2 连接、私有完整快照与房间广播。
- MUSIC / VIDEO 切换后暂停，两个客户端看到一致模式。
- 旧 epoch / modeEpoch 指令被拒，重复 requestId 不重复执行。
- 无 Cookie 的视频入队明确失败，未开始媒体播放。
- 管理 HTTP 使用自己的许可身份，无需先建立 STOMP 身份。
- 房间时间探测返回 serverTime，旧客户端 CONNECT 明确要求升级。
- CONNECT 等待房间恢复和用户注册完成后放行，避免冷启动首次进入被误判游客；初始本人身份和账号配置经房间邮箱读取。

实际协议结果保存在本地 `.video-party-trial/verification/live-protocol.json`；临时验证脚本和隔离数据均被 Git 忽略。

最终包真实停启验证：保留两个 STOMP 成员在线后在启动终端按 Ctrl+C，两个客户端收到连接关闭，进程退出；`logs/trial.log` 没有最终保存失败、room shutdown cleanup、BeanCreationNotAllowed 或 RejectedExecution 异常。重新启动同一数据目录，首次进入恢复 VIDEO 模式及命名管理身份。运行中的媒体进度不在本次验证中，双队列 pending 保存由自动化测试覆盖。

## 实际浏览器页面

使用本机 **无头 Microsoft Edge** 操作真正打包的网页与上述服务；未模拟 iOS / Android 系统，390×844 仅代表窄屏布局检查。

- 进入房间、无 Cookie 提示、模式切换与页面重载通过。
- VIDEO 页面只有一个 `<video>`，无原生 controls，启用 playsInline。
- 展开 / 收起不会创建第二个视频元素；切到音乐后视频元素卸载，切回后重新挂载。
- 视频搜索仅提供 B站入口；房间文档响应包含 `Cache-Control: no-store`。
- 桌面和窄屏没有页面 JavaScript 错误，窄屏没有横向溢出，底部播放按钮完整处于视口内。

页面证据位于本地 `.video-party-trial/verification/video-desktop.png`、`video-expanded.png`、`video-mobile-viewport.png`，汇总位于 `browser-ui.json`。

## 自动化回归

最终后端 `mvnw.cmd -o package` 成功，**224 项测试全部通过，失败 / 错误 / 跳过均为 0**；前端 `npm test` **165 项全部通过**，`npm run build` 成功。后端汇总见本地 `package-tests.log` 和 `target/surefire-reports`。

覆盖房间 mailbox 有界执行和释放、严格持久化切换、双模式 pending 保存、共享投票设置恢复、上下文校验与幂等、视频第一 P 元数据和单段 MP4 校验、空 200 / 204 响应失败释放、解析失败保留、换源与 Cookie 版本隔离、非激活投票保留、关闭 barrier、播放器 reset 保留聊天、前端快照屏障和视频卸载清理。源请求、视频播放时钟与媒体错误使用受控模拟，不能作为真实 CDN 播放证据。

## 未完成的真实验收

B站元数据与 playurl 网络探针已取得符合单段 MP4 形状的响应，详情见 [VT-00 实验记录](watch-together-vt00.md)。完整的浏览器音轨听感、Range / seek、自然失效恢复仍待实播验收，不能宣称真实视频播放闭环或 ≤1.5 秒同步目标通过。下面的修复跟进包含真实视频暂停状态的原生浏览器事件检查。

## 音频碎片与持续缓冲修复跟进

用户在 Edge 播放 `BV1jKHE6qEBY`，多个成员均出现音频多数时间无声、偶有短促碎片及持续 buffering。定位到三处播放器问题：就绪 / 播放回调无条件赋值 `currentTime`，seek 产生的新媒体事件再次触发 seek；源监听返回新数组，心跳替换同源快照也会重新加载；`stalled` 下载事件直接置缓冲且播放前进未清除提示。

修复保留首次进度对齐、明确 seek、源版本更新、正常漂移纠正及网络失败换源。相同源的心跳快照不再触发 load；正常媒体前进清除缓冲，真正等待数据才进入缓冲。

无头 Edge 对同一真实 BV 进行只读检查，未发送房间播放控制。检查时房间暂停在 40.609 秒：

| 观察 | 修复前 | 最终修复版 |
| --- | --- | --- |
| `seeking` 事件 | 159715 次，自循环 | 1 次，初次对齐 |
| `loadedmetadata` 事件 | 4 次 | 1 次 |
| 最终采样状态 | 多为 readyState 1、持续 seeking | readyState 4、seeking false |
| 缓冲提示 | 旧实现会卡住状态 | 12.2 秒观察期内为 0 |

记录为本地 `media-probe-before.json` 与 `media-probe-after-final.json`。修复前存在主线程事件风暴，不能把两次事件数量当作等时长吞吐比较。真实视频暂停时音轨解码计数存在且固定，这是暂停状态预期；这项检查不等于连续声音听感验收。

随后在同一无头 Edge 中创建独立原生视频元素，使用生产 `useVideo` 和浏览器内已有的真实 BV 视频源。没有读取 Cookie 或导出视频源地址，没有发送房间播放控制；原房间前后均暂停在 40.609 秒。独立播放器经过一次本地点击后连续播放 12 秒，采样间进度推进 **11.10 秒**、音频解码字节 **15611 → 104402**；播放阶段 `seeking` / `seeked` / `loadedmetadata` 均为 0，两次同源心跳快照替换没有重载，缓冲采样为 0。记录为 `bili-independent-result.json`。浏览器以 `--mute-audio` 启动，此项证明真实音轨持续解码，不代表扬声器听感。

另有浏览器内合成 VP8 / Opus 双轨媒体回归：生产 `useVideo` 连播 5.20 秒，十次每 500ms 的同源快照替换期间无重载、seek、waiting 或缓冲，音频解码字节 **4311 → 45984**，音量 0.37、静音关闭保持一致。媒体只保存在浏览器内存，未连接房间。记录为 `native-media-result.json`。

前端完整回归更新为 **168 / 168**，生产构建成功（`index-BBnKqkHt.js`）。修复包 `target/MusicParty-video-buffer-fix.jar` 使用已通过 224 项后端测试的相同后端，替换网页资源；ZIP 完整性通过，在独立 18082 端口启动成功，新 HTML / JS 返回 200，HTML 含 `Cache-Control: no-store`，随后正常关闭验证进程。

当前会话可用 18081 修复网页复测，同一房间服务仍在 18080。切换修复 JAR 的命令见 [本地试用](watch-together-trial.md)。

HTTPS、不同网络、真实 iOS Safari、Android 浏览器及 Android 壳均待用户补条件后验收。本次试用配置关闭网易云来源；启动时若本机没有 FFmpeg 会打印音乐直播相关警告，视频路径不使用转码。本次没有真实音乐直播 / FM / DJ 来源运行证据，已有逻辑由回归测试检查。

使用与启动说明：[本地试用](watch-together-trial.md)、[一起看视频使用说明](watch-together-p0-guide.md)。
