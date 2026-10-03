# 构建署名排查记录

排查日期：2026-10-03。项目默认值为 `authorName = ThorNex X Aelsiu`、`backWords = MUSIC PARTY`。

## Windows 的直接来源

`build-windows.ps1` 和 GitHub Release 工作流均通过 Wails 将 `launcher/` 编译为 EXE。两者不生成、不改写 `launcher_config.json`；交付目录只复制 EXE。

实际写入链为：

1. `launcher/pkg/config/config.go` 的 `LoadConfig()` 在配置文件缺失或无法读取时返回默认配置。修复前这里写的是 `ThorNex` / `THORNEX`，是错误 JSON 的直接来源。
2. `launcher/app.go` 加载此配置，启动器前端 `launcher/frontend/src/App.vue` 调用 `LoadConfig()`。前端自己的初始值也重复了相同旧值。
3. 用户点击启动后，前端调用 `SaveConfig()`，Go 的 `AppConfig.Save()` 将配置写入 **EXE 同目录**的 `launcher_config.json`。
4. 启动服务时，`launcher/app.go` 把两个字段作为 `--app.music-api.author-name` 和 `--app.music-api.back-words` 传给 Java；这些显式参数覆盖后端默认配置。因此后端默认署名正确也不能抵消启动器传入的旧署名。

Git 历史核查：`git blame HEAD` 显示上述 Go 与 Vue 的两组旧值均来自 `680d71c4`（2026-05-19）。证据指向启动器遗留默认值未同步；本次排查未发现构建脚本将共同署名替换为旧署名的操作。此证据不能证明修改动机。

`launcher/wails.json` 的 `info.author.name` 是软件包作者元数据，并未读取到这两个运行配置字段中，不属于该 JSON 的来源。

## 各构建路径的影响

| 路径 | 来源与修复前影响 |
|---|---|
| Windows 本地／GitHub Release | 共用 Go 与启动器 Vue 默认值；首次保存可生成两个错误字段。两处已修正。 |
| Ubuntu 首次部署 | `build-ubuntu.ps1` 使用 `Dockerfile` 并附带 `config/application.properties.example`。模板中的共同署名和背景文字已正确，按模板配置的部署不受旧默认影响。 |
| Docker／直接 JAR | 后端 `application.yml` 和 `AppProperties.java` 的默认署名已正确，背景文字仍为 `THORNEX`；未显式配置时受影响。已统一为 `MUSIC PARTY`。 |
| Ubuntu 增量更新 | `build-update.ps1` 构建网页与 JAR；同样使用后端默认值。已部署的配置文件和环境变量会继续覆盖新默认值。 |
| Android | `build-app.ps1` 打包连接服务器的 WebView 客户端，不生成启动器 JSON，也不携带这两项服务端配置。显示值取决于连接的服务器与网页。 |

网页状态 `music-party-web/src/stores/ui.js` 的背景文字初始值也已修正，避免 `/api/config` 返回前或请求失败时出现旧文字；接口成功后仍尊重服务器提供的配置。

`Dockerfile` 的两项构建参数也已统一。它们仅传入网页构建阶段的 `VITE_APP_*` 环境变量，当前网页状态代码没有读取这两个变量，运行阶段以服务端 `/api/config` 为准。因此不能单靠修改 Docker 构建参数修复运行配置。Docker CI 与 Ubuntu 构建脚本只传入版本参数，没有另一组署名覆盖。

## 已有配置与回归防护

新默认值不自动覆盖已有 JSON、部署配置或用户自定义署名。此次已将本地 `launcher/build/bin/launcher_config.json` 的两个错误字段恢复为项目默认值，并逐项验证其余配置保持不变；没有输出其中的密码或 Cookie。

Go 配置回归测试覆盖首次保存生成 JSON、重新加载，以及已有自定义、空值、旧配置的保留。Windows 本地脚本和 Release 工作流在 Wails 打包前执行 `go test -mod=readonly ./pkg/config`，失败即中止构建。

后端 `AppBrandingDefaultsTest` 验证真实 YAML 与 Java 默认值，以及环境变量和显式部署配置的覆盖行为。网页 `branding-defaults.test.js` 覆盖初始值、配置请求失败与自定义配置成功返回的情况。`tests/build-branding.test.ps1` 使用隔离工具夹具验证配置测试失败即停止打包、成功才继续，并检查工作目录、环境变量和已有启动器资源的恢复／保留。

本次验证：Go 配置测试、后端 3 项测试、网页完整测试集 132 项（含 3 项署名测试）与 Windows 构建拦截测试均通过；启动器前端与播放网页的 Vite 生产构建通过。未重新打包完整 Windows EXE、Docker 镜像或 APK，构建工作流本身未在 GitHub 运行。

旧 EXE、旧 JAR、旧镜像不会因源文件修复自动更新；需重新构建发布。旧配置中的值需要单独调整，避免覆盖其他部署设置。
