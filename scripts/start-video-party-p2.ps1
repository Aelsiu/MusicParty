param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.12.1',
    [int]$Port = 18084
)
$ErrorActionPreference = 'Stop'
if ($Port -in @(18080,18081,18082)) { throw 'Existing trial ports are reserved.' }
$p2Project = Split-Path -Parent $PSScriptRoot
$p2Directory = Join-Path $p2Project '.video-party-trial/p2'
$p2BuiltJar = Join-Path $p2Project 'build/p2/MusicParty-video-p2.jar'
$p2Jar = Join-Path $p2Directory 'MusicParty-video-p2.jar'
$p2Java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $p2BuiltJar) -or !(Test-Path -LiteralPath $p2Java)) { throw 'P2 build/JDK21 missing.' }
$p2Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,$Port)
try { $p2Listener.Start() } finally { $p2Listener.Stop() }
New-Item -ItemType Directory -Force -Path $p2Directory | Out-Null
$p2Config = Join-Path $p2Directory 'application.properties'
if (!(Test-Path -LiteralPath $p2Config)) {
    $p2Key = 'P2-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
    @"
server.address=127.0.0.1
server.port=$Port
app.rooms.root-key=$p2Key
app.rooms.database=data/multi-rooms.sqlite
app.rooms.license-file=config/licenses.json
app.music-api.base-url=http://localhost:$Port
app.music-api.netease.enabled=false
app.music-api.bilibili.enabled=true
app.music-api.player.video-max-duration-minutes=180
app.music-api.player.video-max-resolution=UNLIMITED
logging.level.org.thornex.musicparty=INFO
logging.file.name=logs/p2-trial.log
"@ | Set-Content -LiteralPath $p2Config -Encoding utf8NoBOM
    "P2 local temporary test`nURL: http://localhost:$Port`nROOT management license: $p2Key`nBilibili Cookie: configure in this new instance's management UI.`nIndependent empty database; original trial data untouched." | Set-Content -LiteralPath (Join-Path $p2Directory 'access.txt') -Encoding utf8NoBOM
}
Copy-Item -LiteralPath $p2BuiltJar -Destination $p2Jar -Force
$p2Process = Start-Process -FilePath $p2Java -ArgumentList @('-jar',$p2Jar,"--server.port=$Port","--app.music-api.base-url=http://localhost:$Port","--spring.config.additional-location=file:$($p2Config.Replace('\','/'))") -WorkingDirectory $p2Directory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $p2Directory 'stdout.log') -RedirectStandardError (Join-Path $p2Directory 'stderr.log') -PassThru
$p2Process.Id | Set-Content -LiteralPath (Join-Path $p2Directory 'process.pid')
Write-Output "P2 starting: http://localhost:$Port; PID=$($p2Process.Id); isolated directory=$p2Directory"
