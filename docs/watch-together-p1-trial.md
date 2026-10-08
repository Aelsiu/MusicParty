# P1 本地试用

独立入口：[http://localhost:18082/GzKQSJZe](http://localhost:18082/GzKQSJZe)。只监听本机，使用 protocol 3。P0 的 18080 / 18081 端口、原网页 `music-party-web/dist` 和原 JAR 都保留。

启动命令（项目根目录 PowerShell）：

```powershell
.\scripts\start-video-party-p1.ps1
```

独立包在 `.video-party-trial/p1/MusicParty-video-p1.jar`；配置、SQLite 和日志都在同一 `p1` 目录。终端按 Ctrl+C 正常停止。默认本机试用最高许可仍为 `（见本机独立运行配置）`，实际凭据以本地配置为准。首次数据来自原试用 SQLite 的一致性备份；后续两份数据独立。Cookie 继续仅在本地房间管理台管理，不发送给成员。

公开房间可直接 CONNECT；管理者需要以许可进入。若公开入口自动进入游客状态，先返回入口，用「验证许可并管理房间」进入管理身份；也可打开带空 `?pcd=` 的房间链接显示手动入房表单，然后填写 ID 与许可。

## 使用

- 设置 → 其他设置 → 歌词提前显示下方的「视频画质」。默认自动，仅影响本端。长期偏好和当前实际画质分开展示；不兼容设备使用有声 MP4 回退。
- 视频上方的「房间倍速」由 Owner / Root 修改，所有端同步。切模式、换片和换 P 保留 VIDEO 倍速，音乐时钟保持原行为。
- 搜索结果的点播会先载入可信分 P 列表，明确选择一 P 入队；输入 `BV...:pN` 或链接 `?p=N` 会保留指定 P。当前分 P 下拉仅管理者可切，需先解除跳过锁，切后从 0 开始。
- 合格前台观看端持续真实缓冲 2 秒进入全员等待。等待时冻结房间进度并继续本端预缓冲；底部暂停按钮取消等待并保持手动暂停。每轮 15 秒仍未就绪的端退出阻塞资格，顶部显示该端用户名，同一播放实例内重连或换画质不会重新获得阻塞资格。
- protocol 2 页面必须升级。P1 的网页和后端需要配套使用。

## 独立重建

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\Nodejs\npm.cmd' --prefix music-party-web run build -- --outDir ../.video-party-trial/p1-web-dist --emptyOutDir
.\mvnw.cmd -o '-DbuildDirectory=.video-party-trial/p1-target' '-Drevision=video-p1' package
& 'C:\Users\93581\miniconda3\python.exe' scripts/package-video-party-p1.py
```

不要运行 Maven clean，也不要将 P1 网页写入 P0 的 dist。重新打包前停止本 P1 服务，P0 服务无需停止。

实测结果和待补验收见 [P1 验证记录](watch-together-p1-validation.md)。来源、播放器和等待的具体工程参数分别见对应 P1 notes 文档。本机 HTTP、无头 Edge 和窄屏视口均不代替真实 HTTPS、不同网络、手机或扬声器听感验收。
