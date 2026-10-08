param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.12.1',
    [int]$Port = 18082
)
$ErrorActionPreference = 'Stop'
if ($Port -eq 18080 -or $Port -eq 18081) { throw 'P1 默认使用独立端口；18080、18081 保留给 P0。' }
$p1Project = Split-Path -Parent $PSScriptRoot
$p1Directory = Join-Path $p1Project '.video-party-trial\p1'
$p1Jar = Join-Path $p1Directory 'MusicParty-video-p1.jar'
$p1Java = Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $p1Java)) { throw '未找到 JDK 21，请用 -JavaHome 指定。' }
if (!(Test-Path -LiteralPath $p1Jar)) { throw '未找到独立 P1 包，请先按 P1 试用文档构建。' }
$p1Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,$Port)
try { $p1Listener.Start() } finally { $p1Listener.Stop() }
New-Item -ItemType Directory -Force -Path $p1Directory | Out-Null
$p1Config = Join-Path $p1Directory 'application.properties'
if (!(Test-Path -LiteralPath $p1Config)) {
    $trialGeneratedKey = 'VT-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
    @"
server.address=127.0.0.1
app.rooms.root-key=$trialGeneratedKey
app.rooms.database=data/multi-rooms.sqlite
app.rooms.license-file=config/licenses.json
app.music-api.netease.enabled=false
app.music-api.bilibili.enabled=true
app.music-api.player.video-max-duration-minutes=180
logging.level.org.thornex.musicparty=INFO
logging.file.name=logs/p1-trial.log
"@ | Set-Content -LiteralPath $p1Config -Encoding utf8NoBOM
}
Write-Host "P1 入口：http://localhost:$Port （独立数据，只监听本机）"
Write-Host 'Cookie 仅在本地管理台配置。此终端按 Ctrl+C 正常停止。'
Push-Location -LiteralPath $p1Directory
try {
    & $p1Java -jar $p1Jar "--server.port=$Port" "--app.music-api.base-url=http://localhost:$Port" "--spring.config.additional-location=file:$($p1Config.Replace('\','/'))"
} finally { Pop-Location }
