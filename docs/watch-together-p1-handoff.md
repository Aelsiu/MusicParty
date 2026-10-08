# P1 新会话实施交接

日期：2026-10-05。用户明确要求：完成规格后，在新会话开始实现 P1。

## 工作位置与要求

- 当前仓库：`C:/Users/93581/Documents/ChatGPT/MusicParty`，分支 `video-party`，当前 HEAD `707ff89`。这是保存项目 **MusicParty-origin**，不是 `E:/dev/MusicParty` 的同名项目。
- P0 和修复工作尚有大量未提交修改 / 新文件；它们是当前基线，不能 reset、clean、覆盖或从远端重新取一份来丢弃。
- 首先完整阅读本目录 `watch-together-p1-implementation-spec.md` **v1**，再读 `watch-together-validation.md`、`watch-together-trial.md` 和实际源码；`watch-together-p1-reading.md` 为背景，不覆盖 v1。
- 最初设计材料在 `C:/Users/93581/Desktop/design`。旧报告的 videoState、隐式旧协议兼容、应急视频代理和 iOS 版本保证已经被现行 P0 / P1 规则替代。

用户确认：画质各端独立，入口在原设置“歌词提前显示”下方；切 P 从零起播；本轮包括全员缓冲等待，任一合格观看端持续真实缓冲 2 秒触发，单轮最多阻塞 15 秒，超时复用顶部提示框显示阻塞用户名。已实际超时的观看端在同一播放实例内不自动再次阻塞。实现默认权限、倍速、观看资格、protocol 3 和恢复规则见 v1；一般工程参数自主处理并记录，不逐项要求用户再次确认。

## P0 已验证基线

- 后端完整 package 224 项测试通过；最终前端 npm test 168 项通过，生产构建通过。
- 已修复 useVideo readiness / playing 回调反复 seek、同源心跳替换对象导致 load，以及 stalled 假缓冲。保持首次定位、明确小幅 seek、500ms 正常漂移纠正、音量、生命周期和迟到回调隔离。
- 同一真实 BV `BV1jKHE6qEBY:p1` 在独立无头 Edge 播放约 12 秒：进度推进 11.10 秒，音频解码字节 15611 → 104402，播放中 seeking / loadedmetadata 为 0，buffering 为 0。没有改变房间播放；用户随后反馈暂未发现其他问题。
- 合成原生双轨 A/V 连播 5.20 秒、十次同源快照替换，音频字节持续增长，无重复 seek / reload / waiting。
- 这些不能代替真实 DASH、1080p 2.5 小时、HTTPS、跨网络和手机验收；未通过的部分继续如实记录。

## 当前服务与构建保护

- 本地 P0 后端 `127.0.0.1:18080` 运行原 `target/MusicParty-0.0.1-SNAPSHOT.jar`，原网页仍带修复前播放器；Java PID 曾为 31448，具体是否仍存活应只读检查。
- 修复网页 `http://localhost:18081/GzKQSJZe` 为 Vite preview，代理 API / WS 到 18080，**当前从 `music-party-web/dist` 读取页面**。用户可继续使用这一入口。
- 已验证可运行的修复包 `target/MusicParty-video-buffer-fix.jar`，43,795,723 字节，网页 asset `index-BBnKqkHt.js`；启动脚本默认优先选择此包。
- 18082 的修复包烟测服务已经正常关闭，18082 / 18083 可以在检查空闲后用于独立 P1 验证。不要在未告知情况下停止用户当前 18080 / 18081 服务或操作原房间播放。
- **P1 前端构建使用独立 outDir**，例如在前端目录 `npm run build -- --outDir ../.video-party-trial/p1-web-dist`；不要覆盖当前 preview 的 `dist`，否则用户会在 protocol 2 后端上收到 protocol 3 网页。P1 后端 / 数据和 JAR 输出也用独立位置，不能覆盖 / 清理正在运行的原 JAR。
- 隔离试用数据在 `.video-party-trial`，Cookie 已由用户自行在本地管理台配置。不得原样输出配置、SQLite 凭据、Cookie 或完整签名 URL；探针只记录脱敏媒体元数据及计数。不要更改原生产 `config` 或原数据库。

## 本机工具与验证材料

- JDK 21：`C:/Program Files/Java/jdk-21.0.12.1`；Node / npm：`D:/Nodejs/node.exe`、`D:/Nodejs/npm.cmd`。
- Python：`C:/Users/93581/miniconda3/python.exe`。文件型工具可能打印自身位置警告，但脚本正常执行。
- 可用 Playwright：`C:/Users/93581/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright`，使用已安装 Edge channel；此前 CUA 未成功，不能把失败的 UI 工具结果作为浏览器验证。
- Windows sandbox 下 Maven Mockito JVM attach、Vite realpath、Edge launch 曾需要经自动审批的隔离外执行。先采用正常安全命令，按真实失败申请适当权限；不是用户阻塞项。避免运行 Maven clean 删除正在使用的 JAR。
- 前端构建可将 TEMP / TMP 设为前端 `.frontend-tmp`。后端测试使用明确 JAVA_HOME 和离线 wrapper；先跑与变更有关的有效测试，再完成全量回归。
- 安全探针和 JSON 位于 `.video-party-trial/verification`，包括 `buffer-fix-validation.json`、`bili-independent-result.json`、`native-media-result.json`、`media-probe-after-final.json`。临时目录、dist、node_modules 与产物均被 Git 忽略，不要将凭据或临时来源加入提交。

## 开始实施

在新会话立即开始工作：先盘点接口 / 依赖和真实 DASH 来源契约，按 v1 接入双轨、画质、续期、分 P、倍速与等待，合理使用协作子代理。保持房间协调器、邮箱、单激活快照和权限边界，不重做 P0 架构。

优先验证 DASH 清单适配、CDN CORS、Range、codec 和连续声音，保留 MP4 回退；若真实来源验证失败，定位问题并继续完成不受影响部分，不悄悄代理视频或伪称验证通过。交付可运行的本地 P1 包和独立试用入口，记录测试范围、结果和尚需用户补条件的真实验收。用户没有要求在本会话部署、推送或创建 PR。
