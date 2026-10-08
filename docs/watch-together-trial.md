# P0 本地试用

最新修复产物：`target/MusicParty-video-buffer-fix.jar`，已包含修复网页。启动脚本优先选择此包，未找到时使用原 `target/MusicParty-0.0.1-SNAPSHOT.jar`。需要本机 JDK 21；不需要安装前端依赖才能运行 JAR。

在项目根目录的 PowerShell 中执行：

```powershell
.\scripts\start-video-party-trial.ps1
```

JDK 在其他位置时：

```powershell
.\scripts\start-video-party-trial.ps1 -JavaHome 'C:\Tools\jdk-21'
```

入口是 [http://localhost:18080](http://localhost:18080)，只监听本机。默认试用最高许可为 `（见本机独立运行配置）`，由 `.video-party-trial/application.properties` 保存。它仅用于此隔离试用环境，实际部署使用自己的许可。运行数据在 `.video-party-trial/data`，原项目 `config/application.properties` 和数据库不被此脚本覆盖。

启动终端按 **Ctrl+C** 停止，等待退出后再启动；正常退出会取消未完成解析并保存待播队列和设置。不要重复启动占用同一端口。

## 2026-10-05 音频与缓冲修复版

此次实测发现并修复了播放器反复 seek、相同视频源被心跳重新加载，以及 `stalled` 被误判为播放缓冲的问题。

当前会话的修复网页入口是 [修复版试用房间](http://localhost:18081/GzKQSJZe)，复用正在运行的 18080 房间服务。打开这个入口后重新连接并播放；公开房间直接 CONNECT，私有房间按页面要求填写 ID 和许可。两个浏览器都应使用 18081 入口。18081 是临时本地网页预览，当前 18080 服务仍携带修复前网页。

可独立运行的修复包为 `target/MusicParty-video-buffer-fix.jar`。需要切换到修复 JAR 时，先在原服务启动终端按 Ctrl+C，等待退出，再执行：

```powershell
.\scripts\start-video-party-trial.ps1 -JarPath .\target\MusicParty-video-buffer-fix.jar
```

随后使用原 18080 入口。脚本继续使用同一隔离试用数据目录；重启后的当前视频与进度按 P0 规则清空，队列和配置恢复。修复包已在另一隔离目录、独立端口完成启动及网页资源检查。

## 试用步骤

1. 打开入口，填写用户 ID，点击管理许可入口，输入试用最高许可；进入或创建一个试用房间。也可直接打开 [视频 P0 试用房间](http://localhost:18080/GzKQSJZe)，填写 ID 和试用许可，先点 JOIN ROOM，再点 CONNECT。
2. 用填写了 ID 的管理身份进入房间，打开管理台，在「平台凭据 / Credentials」中填入你自己的完整 B站 Cookie，只在本地管理台填写。若管理台已弹出，配置后点击右上角关闭，回到视频页面。
3. 音乐房间点击导航栏「一起听歌 ⇄」，确认切换到一起看视频。已创建的试用房间默认处于视频模式，切回音乐使用「一起看视频 ⇄」。
4. 搜索或输入 `BV1GJ411x7h7`，点入队，再点击底部播放。若浏览器提示，点击视频上的「开始观看」。
5. 第二个桌面浏览器使用同一房间配对码和另一个 ID 进入，检查播放、暂停、seek、下一条、刷新、切回音乐。Cookie 配置无需在成员端重复填写。
6. 切回音乐再切视频，确认两边现场保留且切换后暂停；重启后确认队列与模式恢复、当前进度不恢复。

完整新功能说明见 [一起看视频使用说明](watch-together-p0-guide.md)。实测范围与限制见 [验证记录](watch-together-vt00.md) 和 [本次交付验证](watch-together-validation.md)。

当前本机 HTTP 入口不代替部署域名 HTTPS、不同网络、iOS Safari、Android 浏览器及 Android 壳验收。上述测试条件按本次用户选择留待补测，视频实际直连与稳态同步指标尚未宣称通过。
