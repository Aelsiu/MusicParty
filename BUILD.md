# 构建说明

在仓库根目录运行下面的脚本。先按目标准备工具，再用预检参数检查环境，最后执行构建命令。

| 目标 | 脚本 | 构建主机 | 产物 |
|---|---|---|---|
| Windows 启动器 | `build-windows.ps1` | Windows 10/11 x64 | `dist/windows/MusicParty-windows-<version>-<commit>.exe` |
| Ubuntu 首次部署 | `build-ubuntu.ps1` | Windows 或 Ubuntu，运行 Linux Docker 容器 | `dist/ubuntu/<arch>/MusicParty-ubuntu-<version>-<commit>.tar.gz` |
| Ubuntu 已有部署增量更新 | `build-update.ps1` | Windows 或 Ubuntu | `dist/update/MusicParty-update-<version>-<commit>.tar.gz` |
| Android 客户端 | `build-app.ps1` | Windows 或 Ubuntu | `dist/android/<variant>/MusicParty-android-<version>-<commit>.apk` |

Ubuntu 部署包包含完整运行镜像；增量包只更新已部署应用；APK 是连接现有 Music Party 服务器的客户端，需要另外部署服务端。

## 版本与产物命名

四个脚本统一使用 `MusicParty-环境-版本号-提交号` 命名最终交付文件。环境分别为 `windows`、`ubuntu`、`update`、`android`；`<version>` 只读取仓库根目录的 `VERSION`，初始值为 `1.3.9`；`<commit>` 使用 `git rev-parse --short=7 HEAD` 的结果，通常为 7 位，在 Git 需要消除歧义时可能更长。脚本不会根据当前分支或 tag 自动替换版本号。

例如根 `VERSION` 为 `1.3.9`、短提交号为示例 `abcdef0` 时：

```text
dist/windows/MusicParty-windows-1.3.9-abcdef0.exe
dist/ubuntu/amd64/MusicParty-ubuntu-1.3.9-abcdef0.tar.gz
dist/update/MusicParty-update-1.3.9-abcdef0.tar.gz
dist/android/debug/MusicParty-android-1.3.9-abcdef0.apk
```

Ubuntu 的 `<arch>` 为 `amd64` 或 `arm64`，Android 的 `<variant>` 为 `debug`、`release` 或 `release-unsigned`。架构和 APK 类型只放在输出子目录，不额外插入文件名。Ubuntu 与更新包解压后的顶层目录与压缩包主文件名相同，例如 `MusicParty-ubuntu-1.3.9-abcdef0/`。

修改应用发布版本时，**只编辑根目录 `VERSION`，使用 `主版本.次版本.修订版本` 格式，然后提交修改再构建**；也支持 `1.4.0-beta.1` 这样的预发布版本，数值部分和纯数字的预发布标识不能有前导零，不添加 `v` 前缀。不要分别修改前端、Maven 或 Android 的版本来指定这四个脚本的产物版本。脚本统一设置前端 `VITE_APP_VERSION` 和 Maven `revision`，网页控制台的 `window.__APP_VERSION__` 返回应用版本号，例如 `1.3.9`。Android `VersionName` 同样使用根版本号。

GitHub Release 和 Docker CI 也从根 `VERSION` 读取应用版本，EXE / APK 交付文件沿用同一命名规则。Release 发布目标仍由触发 tag 或手动填写的 `release_tag` 决定，Docker Registry 标签仍按现有分支、提交和 tag 规则生成，均与应用版本来源分开。

Ubuntu / 更新包内的 `APP_VERSION` 保存应用版本号，包内 `VERSION` 保留完整 Git 提交号以兼容已有更新脚本；两者都纳入校验清单。不要把包内 `VERSION` 当作源码仓库根 `VERSION`。预检显示当前版本和提交，实际构建开始时读取两者，交付前再次检查；构建过程中更换 `HEAD` 或修改根版本号会报错，需重新构建。已有旧名称产物保留原样，重新构建才使用新命名规则。

## 通用准备

- 四个脚本都需要 Git **2.x**。使用 Git 克隆仓库，保留 `.git`，获取方式见 [Git 官方下载页](https://git-scm.com/downloads/)。Ubuntu 可以使用 `sudo apt install git`。
- Ubuntu 完整包与增量包要求 **PowerShell 7.0 或以上**；Windows 启动器和 Windows 上的 APK 构建支持 Windows PowerShell 5.1 或以上；Ubuntu 上的 APK 构建使用 PowerShell 7。建议统一使用 PowerShell 7：Windows 见 [官方安装说明](https://learn.microsoft.com/en-us/powershell/scripting/install/install-powershell-on-windows)，Ubuntu 见 [官方安装说明](https://learn.microsoft.com/en-us/powershell/scripting/install/install-ubuntu)。安装后命令为 `pwsh`，Windows 自带的 `powershell.exe` 通常仍是 5.1。
- 安装工具后重新打开终端。通过 `PATH` 使用工具，或通过各脚本提供的路径参数指定。Java 构建需要完整 **JDK**，仅有 JRE 不够。
- 首次构建需要联网下载 npm、Maven、Go、Gradle 依赖或 Docker 镜像。后续缓存能减少下载；缓存未齐全时不能用离线模式完成首次构建。
- 脚本读取当前 `HEAD`，**不会自动 `git pull`**。需要远程最新版本时，先自行拉取并解决本地修改。Ubuntu 部署包与增量包在应用源码或依赖定义有未提交修改时，会拒绝标记为当前提交；提交准备发布的修改后重试。

Windows 示例使用 PowerShell，Ubuntu 可在 Bash 中使用 `pwsh -NoProfile -File ./build-xxx.ps1`。例如：

```bash
pwsh -NoProfile -File ./build-ubuntu.ps1 -CheckEnvironment
```

## Windows：build-windows.ps1

### 环境与外部工具

| 工具 | 最低要求 / 约束 | 获取方式 |
|---|---|---|
| 构建系统 | Windows 10/11 x64，PowerShell 5.1+ | 系统自带 PowerShell，或安装上面的 PowerShell 7 |
| Git | **2.x**，用于读取提交号 | [Git 官方下载](https://git-scm.com/downloads/) |
| Go | **1.23.12 或以上的稳定版**，Windows amd64 | [Go 官方下载](https://go.dev/dl/)，选择 `windows-amd64` |
| Node.js 与 npm | Node.js **22.12.0+**，Windows x64；npm 7+ | [Node.js 官方下载](https://nodejs.org/en/download)，安装包含 npm 的版本 |
| JDK | **21**，Windows x64，必须包含 `java`、`javac`、`jlink` | [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)，选择 JDK、Windows、x64 |
| Maven Wrapper | 仓库自带 `mvnw.cmd`，使用 Maven **3.9.12** | 无需单独安装 Maven；Wrapper 首次运行自动获取分发包 |
| Wails CLI | **2.12.0**，与 `launcher/go.mod` 一致 | 按下面的固定版本命令安装，参考 [Wails 安装说明](https://wails.io/docs/gettingstarted/installation/) |
| NCM API Enhanced 源码 | 包含 `app.js`、`package.json`、`main.js`、`server.js`、`module/`、`util/` 等完整文件 | [API Enhanced 仓库](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)，克隆到 `external-api-source` 或指定其他目录 |
| FFmpeg | **7.0.1+**，Windows x64 的静态构建，单个 `ffmpeg.exe` 能独立运行 | [FFmpeg 官方下载入口](https://ffmpeg.org/download.html)，其中 Windows 构建链接由第三方提供，下载后解压 |
| WebView2 Runtime | 运行启动器需要可用的 Evergreen Runtime | [Microsoft WebView2 分发说明](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution)；部分 Windows 已预装，可用 `wails doctor` 检查 |

安装 Wails 并获取默认 API 源码：

```powershell
go install github.com/wailsapp/wails/v2/cmd/wails@v2.12.0
# 将 go env GOPATH 输出目录下的 bin 加入 PATH，重新打开终端
git clone https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced.git external-api-source
```

`build-local.ps1` 已更名为 `build-windows.ps1`。脚本使用本机已安装的 Go，接受较高的稳定版本，不再强制下载或切换到 1.23.12。仓库 CI 的复现基线仍是 1.23.12；本次已用 Go 1.27.1 编译验证启动器及 Wails 2.12.0 依赖，没有发现阻断问题。此检查不等同于完整分发包和未来所有 Go 版本的运行验证。Go 的兼容原则见 [官方兼容性说明](https://go.dev/doc/go1compat)。Wails CLI 则仍应与项目依赖版本一致。

### 一键构建

```powershell
# 只检查工具；无需提供 API 或 FFmpeg
.\build-windows.ps1 -CheckEnvironment

# 构建，替换为实际的静态 FFmpeg 文件
.\build-windows.ps1 -FfmpegPath 'C:\Tools\ffmpeg\bin\ffmpeg.exe'

# API 源码不在默认目录时
.\build-windows.ps1 -NeteaseApiPath 'D:\Sources\api-enhanced' `
  -FfmpegPath 'C:\Tools\ffmpeg\bin\ffmpeg.exe'
```

脚本先运行启动器配置的 Go 回归测试，再安装并测试前端依赖、构建后端、在打包副本中安装 API 生产依赖，最后构建 Wails 启动器，不改变外部 API 源码目录。配置测试失败会中止构建；GitHub Release 的 Windows 构建也执行此检查。EXE 内包含本机 Node 运行时、NCM API、前后端 JAR、FFmpeg 和由 JDK 21 裁剪的 JRE。只传入 `ffmpeg.exe` 会只打包这个文件，所以不要使用还依赖同目录 DLL 的 FFmpeg 构建。

最终交付输出为 `dist/windows/MusicParty-windows-<version>-<commit>.exe`。最终用户无需安装 Go、Node、Java 或 Maven，但需要 WebView2 Runtime。首次运行按 [多房间部署文档](docs/multi-rooms-deployment.md) 编辑 EXE 同目录的 `config/application.properties`，设置最高许可和外部访问地址。

项目默认署名为 `ThorNex X Aelsiu`，默认背景文字为 `MUSIC PARTY`。Windows 的 `launcher_config.json` 在用户保存启动器配置时生成于 EXE 同目录，并非构建脚本生成；其中已有的配置会继续保留。更新 EXE 不会自动覆盖旧 JSON，如需恢复这两个默认值，请仅修改 `authorName` 和 `backWords`，保留其他配置。各平台的来源与覆盖顺序见 [构建署名排查记录](docs/build-branding-audit.md)。

## Ubuntu 首次部署：build-ubuntu.ps1

### 构建环境与外部工具

| 工具 | 最低要求 / 约束 | 获取方式 |
|---|---|---|
| PowerShell / Git | PowerShell **7.0+**、Git **2.x** | 见通用准备 |
| Docker | Docker Engine **24.0+**；必须能运行 **Linux 容器** | Ubuntu 见 [Docker Engine 官方安装说明](https://docs.docker.com/engine/install/ubuntu/)；Windows 见 [Docker Desktop 安装说明](https://docs.docker.com/desktop/setup/install/windows-install/) |
| tar | 支持 `tar -czf`、`tar -xzf` 的版本 | Windows 10/11 自带 `tar.exe`；Ubuntu 使用 `sudo apt install tar` |

Windows 推荐使用 Docker Desktop 的 WSL 2 后端，按其安装页准备系统和 WSL 版本，并切换到 Linux containers。Ubuntu 使用 Docker Engine。构建不需要在主机安装 Node.js、JDK、Maven、Go、FFmpeg 或 API 源码，这些组件在 Docker 中构建或由镜像提供。

### 一键构建

```powershell
.\build-ubuntu.ps1 -CheckEnvironment
.\build-ubuntu.ps1

# 为 ARM64 Ubuntu 服务器构建
.\build-ubuntu.ps1 -Platform linux/arm64

# 可指定 API 镜像版本或摘要；默认 moefurina/ncm-api:latest
.\build-ubuntu.ps1 -NeteaseApiImage 'moefurina/ncm-api:latest'
```

默认目标为 `linux/amd64`。目标架构与构建主机不同时，Docker 还需要能执行目标架构的构建步骤；Docker Desktop 一般提供模拟支持，纯 Linux Engine 需另外配置模拟或在相同架构主机上构建。指定的 API 镜像也必须提供目标架构。

脚本在 Docker 中运行前后端测试并构建主应用，然后拉取指定的 NCM API 镜像，导出同架构的两个镜像。默认产物为：

```text
dist/ubuntu/amd64/MusicParty-ubuntu-<version>-<commit>.tar.gz
```

包内包含 `images.tar`、`docker-compose.yml`、`deploy-docker.sh`、空密钥的 `config/application.properties.example`、`APP_VERSION`、记录完整提交号的 `VERSION`、镜像标签信息、`SHA256SUMS` 和部署 README。包内没有真实许可、Cookie 或数据库。采用 `latest` API 镜像时，不同时间的构建可能获取不同 API 版本；需要固定外部组件时使用明确标签或镜像摘要。

### 服务器准备与首次部署

推荐 Ubuntu **22.04 LTS 或以上**，架构必须与包一致；准备 Docker Engine **24.0+**、Docker Compose 插件 **2.20+**、Bash、`tar`、`sha256sum` 和 `curl`。Compose 插件见 [官方安装说明](https://docs.docker.com/compose/install/linux/)，其他工具可通过 `sudo apt install bash tar coreutils curl` 安装。

把压缩包上传、解压到部署目录，按照包内 README 执行 `deploy-docker.sh`。脚本校验并 `docker load` 导入镜像；配置最高许可和访问地址后用 Compose 启动。镜像已装在包里，部署阶段无需从仓库下载应用或 API 镜像。完整命令见 [Ubuntu 部署包说明](docs/ubuntu-deployment.md)。

## Ubuntu 增量更新：build-update.ps1

### 构建环境与外部工具

| 工具 | 最低要求 / 约束 | 获取方式 |
|---|---|---|
| PowerShell / Git | PowerShell **7.0+**、Git **2.x** | 见通用准备 |
| Node.js 与 npm | Node.js **22.12.0+**，使用随其安装的 npm | [Node.js 官方下载](https://nodejs.org/en/download) |
| JDK | **21**，完整 JDK，`JAVA_HOME` 应指向它 | [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) |
| Maven | **3.6.3+**，或仓库 Maven Wrapper | [Maven 安装说明](https://maven.apache.org/install.html)；Wrapper 使用 **3.9.12** |
| tar | 支持 gzip 压缩 / 解压 | Windows 自带 `tar.exe`；Ubuntu 使用 `sudo apt install tar` |

构建主机不需要 Docker、Go、Wails、FFmpeg 或 API 源码。服务器则需要已有的、使用本仓库目录挂载方式的 Compose 部署，以及 Docker、Compose v2、Bash、`tar`、`sha256sum` 和 `curl`。

### 一键构建

```powershell
.\build-update.ps1 -CheckEnvironment
.\build-update.ps1

# 依赖已经缓存完毕时，禁止 npm/Maven 下载
.\build-update.ps1 -Offline
```

每次构建都会运行 `npm ci`，根据锁文件重新安装依赖，随后执行前后端测试、构建包含前端的新 JAR，并打包更新脚本。`-InstallDependencies` 保留为旧命令的兼容参数；默认流程已包含依赖安装。`-Offline` 禁止 npm/Maven 下载；没有已安装 Maven 或匹配版本的 Wrapper 分发缓存时会提前拒绝，缓存不完整时应先在线构建。它不涉及服务器工具更新。

输出为 `dist/update/MusicParty-update-<version>-<commit>.tar.gz`，包含新 `app.jar`、增量 Dockerfile、`update-docker.sh`、`APP_VERSION`、记录完整提交号的 `VERSION`、校验和及更新说明。服务器更新复用现有 Java/FFmpeg 镜像层，只替换主应用，保留 NCM API 容器、配置、数据库和音乐缓存。上传、备份、验证与回退命令见 [增量更新文档](docs/incremental-update.md)。

如果版本变更要求升级 Java、FFmpeg 或 API，不能靠 JAR 增量包完成，需另外升级相应组件。已有部署有自定义服务名、端口、挂载路径时，先确认与更新脚本的约束一致。

## Android：build-app.ps1

### 环境与外部工具

| 工具 | 最低要求 / 约束 | 获取方式 |
|---|---|---|
| PowerShell | Windows 使用 **5.1+**；Ubuntu 使用 **7.0+** | 见通用准备 |
| Git | **2.x**，用于读取提交号 | [Git 官方下载](https://git-scm.com/downloads/) |
| JDK | **17+**，建议 **17 或 21** | [Eclipse Temurin](https://adoptium.net/temurin/releases/)；设置 `JAVA_HOME` 或使用 `-JavaHome` |
| Gradle | **8.7+ 的 8.x 版本**，建议 **8.9** | [Gradle 官方发行包](https://gradle.org/releases/)，下载 8.9 的 binary-only ZIP，解压并把 `bin` 加入 `PATH`，或使用 `-GradlePath` |
| Android SDK | `platforms;android-35`、`build-tools;34.0.0`，以及已接受的许可证 | [Android Studio / Command-line Tools 官方下载](https://developer.android.com/studio)，使用 SDK Manager 安装 |
| Android Gradle Plugin | 项目固定 **8.6.1**，由 Gradle 下载 | 兼容要求见 [AGP 8.6 官方说明](https://developer.android.com/build/releases/agp-8-6-0-release-notes) |

仓库没有 Android Gradle Wrapper，需要提前安装 Gradle。APK 构建不需要 Go、Wails、Docker、Node、Maven、FFmpeg 或 API 源码。JDK 和 Gradle 的版本必须相互兼容；使用 JDK 17/21 和 Gradle 8.9 可避免任意升级工具链引入的组合问题。

使用 Android Studio 的 SDK Manager 安装 Android 15（API 35）和 Build Tools 34.0.0。只用命令行时，将官方 Command-line Tools 放到 SDK 目录下的 `cmdline-tools/latest`，再执行：

```powershell
# 替换 SDK 路径；Ubuntu 相应使用 sdkmanager
$sdk = 'C:\Users\你的用户名\AppData\Local\Android\Sdk'
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-35" "build-tools;34.0.0" "platform-tools"
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

SDK 工具的使用见 [sdkmanager 官方文档](https://developer.android.com/tools/sdkmanager)。`platform-tools` 的 `adb` 用于连接手机安装 / 调试，并非编译 APK 的必要依赖。

### 一键构建与签名

```powershell
.\build-app.ps1 -CheckEnvironment
.\build-app.ps1

# 未加入 PATH 时显式指定目录 / 可执行文件
.\build-app.ps1 -JavaHome 'C:\Tools\jdk-21' `
  -AndroidSdk 'C:\Users\你的用户名\AppData\Local\Android\Sdk' `
  -GradlePath 'C:\Tools\gradle-8.9\bin\gradle.bat'

# 生成未签名 Release；需签名后才能安装
.\build-app.ps1 -BuildType Release

# 使用自己的发布密钥签名；先准备下文要求的密码环境变量
.\build-app.ps1 -BuildType Release -KeystorePath 'D:\Keys\music-party.jks' -KeyAlias music-party
```

默认输出 `dist/android/debug/MusicParty-android-<version>-<commit>.apk`，已使用调试密钥签名，可安装用于测试。未提供密钥的 Release 放在 `dist/android/release-unsigned/`，提供密钥则由 SDK 的 `apksigner` 签名并验证，放在 `dist/android/release/`；三种类型的文件名都采用同一格式。每个 APK 同时输出 `MusicParty-android-<version>-<commit>.apk.sha256`。签名需要环境变量 `MUSICPARTY_KEYSTORE_PASSWORD`；独立的密钥密码使用 `MUSICPARTY_KEY_PASSWORD`，不设置时使用 keystore 密码。若使用不同的密码环境变量名，可通过 `-StorePasswordEnvironment`、`-KeyPasswordEnvironment` 指定。密钥可用 JDK 的 `keytool -genkeypair` 创建，参考 [Android 官方签名说明](https://developer.android.com/studio/publish/app-signing) 和 [apksigner 文档](https://developer.android.com/tools/apksigner)。后续覆盖安装必须沿用同一个签名密钥，并提高 `VersionCode`。

`VersionName` 自动读取根 `VERSION`；兼容参数 `-VersionName` 只能省略或传入与根版本相同的值，传入其他值会被拒绝。`VersionCode` 默认按数值部分的 `major * 10000 + minor * 100 + patch` 计算，例如 `1.3.9` 得到 `10309`、`1.4.0-beta.1` 得到 `10400`，`0.0.0` 回落为 `1`。若次版本或修订版本达到 100，默认公式会发生碰撞，需显式提供 `-VersionCode`；版本号范围为 1–2100000000。需要同一应用版本制作可覆盖安装的新 APK 时，可显式使用更高的 `-VersionCode`，例如 `-VersionCode 10310`。

`-OutputDirectory` 更换输出根目录，仍会添加 `debug`、`release` 或 `release-unsigned` 子目录；`-GitPath` 可指定 Git 可执行文件，默认使用 `git`；`-Clean` 清理 Android 构建缓存后构建；`-Offline` 只使用已有 Gradle 依赖缓存；`-CheckOnly` 是 `-CheckEnvironment` 的兼容别名。SDK 目录可由 `-AndroidSdk`、`android/local.properties` 中的 `sdk.dir` 或 `ANDROID_HOME` 指定，多个配置应保持一致。脚本不会自动安装 JDK、Gradle 或 Android SDK。

客户端最低 Android **7.0（API 24）**，编译 / 目标 API 为 35。安装后在客户端配置 Music Party 服务地址，例如 `https://music.example.com`。服务必须能被手机访问；手机上的 `localhost` 是手机本身，不能填写构建电脑或服务器的 `localhost`。APK 不携带 Java 后端、NCM API 或 FFmpeg。

## 常见报错

| 报错 / 现象 | 处理方法 |
|---|---|
| `pwsh`、`node`、`mvn`、`go`、`wails`、`gradle` 等找不到 | 安装对应工具，将可执行文件目录加入 `PATH` 后重新打开终端。Wails 默认在 `(go env GOPATH)/bin`。先运行目标脚本的 `-CheckEnvironment`。 |
| `running scripts is disabled` / PowerShell 执行策略 | 确认脚本来源后，可在当前会话运行 `Set-ExecutionPolicy -Scope Process Bypass` 再构建；只影响该会话。组织策略限制时按本单位规则处理。 |
| Node 版本不够 / Vite 提示 unsupported engine | 升级至 22.12.0+，检查 `node --version` 和命令实际位置；多个 Node 安装并存时调整 `PATH`。本项目 Vite 7 依赖有自己的版本约束，不能用较早的 Node 22。 |
| `JAVA_HOME` 无效、`javac` / `jlink` 不存在、`release version 21 not supported` | Windows / 更新构建安装完整 JDK 21，确保 `JAVA_HOME` 与 `PATH` 指向同一套工具。Android 使用 JDK 17/21。不要把 `JAVA_HOME` 指向 `bin`。 |
| npm / Maven / Go / Gradle 超时、证书错误、依赖解析失败 | 确认网络、系统时间、代理和软件仓库可访问，按对应工具官方文档配置代理 / 信任证书，再重试。首次运行不要使用 `-Offline`。 |
| `npm ci` 失败或 `package-lock.json` 与依赖不一致 | 使用项目要求的 Node/npm；若修改了 `package.json`，先按开发流程更新锁文件并提交，再构建。不要靠重复使用旧 `node_modules` 绕过锁文件错误。 |
| Maven Wrapper 下载失败 | 允许访问 Maven 仓库；增量包构建也可安装 Maven 3.6.3+ 并加入 `PATH`，Windows 启动器使用仓库 Wrapper。离线构建还要求 Wrapper 分发包、插件及依赖已完整缓存。 |
| `Application source ... uncommitted changes` | 检查 `git status --short`，提交准备发布的应用源码 / 依赖修改后重试。构建不会自动提交或拉取代码。 |
| 缺少根 `VERSION`、格式错误或 APK `VersionName` 不一致 | 恢复仓库根 `VERSION`，填写 `主版本.次版本.修订版本` 并提交；APK 通常省略 `-VersionName`，只在需要时指定 `-VersionCode`。 |
| 构建过程中版本号或 `HEAD` 改变 | 停止并行切换分支、拉取或修改版本文件的操作，确定目标提交和版本后重新构建。 |
| Docker daemon 不可用 / Windows 容器模式 | 启动 Docker Desktop 或 Docker 服务，切换 Linux containers，检查 `docker info`。Ubuntu 账户需有 Docker 权限。 |
| `exec format error` / API 镜像没有对应 platform | 目标包架构应与服务器一致；确认 API 镜像包含该架构。跨架构构建需配置模拟，或改用对应架构构建主机。 |
| Ubuntu 镜像拉取 / 构建失败 | 构建主机需要访问 Docker Registry、npm / Maven 仓库及 Alpine 软件源。检查输出中失败的步骤。部署包成功导出后，服务器无需再拉取镜像。 |
| `NeteaseApiPath` 内容不完整 | 指向 API Enhanced 的完整源码目录，不要指向 Docker 镜像、旧 API 分支或其某个子目录。 |
| FFmpeg 无法运行 / 缺 DLL | 选择 Windows x64 静态构建，先直接运行 `ffmpeg.exe -version`。Linux FFmpeg、共享库构建或错误架构不能放入 Windows 启动器。 |
| Go / Wails 版本不满足要求 | 使用稳定 Go 1.23.12+；按固定版本命令重装 Wails 2.12.0。检查已有 `GOTOOLCHAIN` 配置是否强制了旧版本。 |
| Windows 启动器空白 / 找不到 WebView2 | 安装或修复 Evergreen WebView2 Runtime，运行 `wails doctor` 检查；查看启动器日志和配置。 |
| Android `SDK location not found` / 缺 Android 35 | 用 `-AndroidSdk` 指定 SDK 根目录，或设置 `ANDROID_HOME`；SDK Manager 安装 API 35 和 Build Tools 34.0.0。 |
| Android licenses not accepted | 使用相同的 SDK 目录运行 `sdkmanager --licenses`，按提示接受许可证。 |
| Android Gradle / Java 不兼容 | 优先使用 Gradle 8.9 + JDK 17/21。不要误用系统的旧 Gradle，或未经验证的 Gradle 9；可用路径参数显式指定。 |
| Release APK 无法安装 / 签名冲突 | 未签名 APK 先签名；覆盖安装使用同一签名密钥及更高 `VersionCode`。调试版与不同密钥的发布版不能直接覆盖安装。 |
| Ubuntu 更新提示挂载目录不匹配 | `--project-dir` 必须是正在使用的 Compose 部署目录，包含 `config/`、`music_party/data/`、`music_party/cached_media/`；自定义部署先适配脚本。 |
| 服务启动失败 / `app.rooms.root-key` 无效 | 在实际部署配置中填入 8–16 位无空白的 ASCII 可见字符最高许可，配置访问地址，检查日志。生成的示例故意留空，需要首次部署时填写。 |
| 更新后健康检查失败 | 保留脚本打印的备份路径、镜像和日志，按 [增量更新文档](docs/incremental-update.md) 执行回退；不要删除备份或覆盖数据库。 |

测试失败时查看第一个失败的测试及其堆栈，修复后重新构建；不要用跳过测试的方式制作发布包。预检成功只代表依赖满足检查条件，最终构建、签名及目标设备 / 服务器验证仍需以实际运行结果为准。
