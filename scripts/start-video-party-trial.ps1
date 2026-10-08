param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.12.1',
    [string]$JarPath = ''
)
$ErrorActionPreference = 'Stop'
$trialProject = Split-Path -Parent $PSScriptRoot
$trialDirectory = Join-Path $trialProject '.video-party-trial'
$trialJar = Join-Path $trialProject 'target\MusicParty-video-buffer-fix.jar'
if (!(Test-Path -LiteralPath $trialJar)) { $trialJar = Join-Path $trialProject 'target\MusicParty-0.0.1-SNAPSHOT.jar' }
if ($JarPath) { $trialJar = (Get-Item -LiteralPath $JarPath).FullName }
$trialJava = Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $trialJava)) { throw "未找到 JDK 21，请通过 -JavaHome 指定安装目录。" }
if (!(Test-Path -LiteralPath $trialJar)) { throw "未找到试用 JAR：$trialJar" }
New-Item -ItemType Directory -Force -Path $trialDirectory | Out-Null
$trialConfig = Join-Path $trialDirectory 'application.properties'
if (!(Test-Path -LiteralPath $trialConfig)) {
    $trialGeneratedKey = 'VT-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
    @"
server.port=18080
server.address=127.0.0.1
app.rooms.root-key=$trialGeneratedKey
app.rooms.database=data/multi-rooms.sqlite
app.rooms.license-file=config/licenses.json
app.music-api.base-url=http://localhost:18080
app.music-api.netease.enabled=false
app.music-api.bilibili.enabled=true
app.music-api.player.video-max-duration-minutes=120
logging.level.org.thornex.musicparty=INFO
logging.file.name=logs/trial.log
"@ | Set-Content -LiteralPath $trialConfig -Encoding UTF8
}
Write-Host '试用入口：http://localhost:18080 （仅本机）'
Write-Host "试用许可在 $trialConfig；许可仅保存在该运行配置中。Cookie 仅在房间管理台填写。"
Write-Host '此终端按 Ctrl+C 停止；数据保存在独立的 .video-party-trial 目录。'
Push-Location -LiteralPath $trialDirectory
try {
    & $trialJava -jar $trialJar "--spring.config.additional-location=file:$($trialConfig.Replace('\','/'))"
} finally { Pop-Location }
