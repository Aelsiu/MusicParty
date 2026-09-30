param(
    [string]$NeteaseApiPath = (Join-Path $PSScriptRoot 'external-api-source'),
    [Parameter(Mandatory = $true)][string]$FfmpegPath
)
$ErrorActionPreference = 'Stop'
$env:GOTOOLCHAIN = 'go1.23.12'
$projectDirectory = [IO.Path]::GetFullPath($PSScriptRoot)
$apiDirectory = (Resolve-Path -LiteralPath $NeteaseApiPath).Path
$ffmpegBinary = (Resolve-Path -LiteralPath $FfmpegPath).Path
if (-not (Test-Path -LiteralPath (Join-Path $apiDirectory 'app.js'))) { throw 'NeteaseApiPath must contain the API Enhanced app.js' }
$nodeBinary = (Get-Command node.exe -ErrorAction Stop).Source
if ([int]((& $nodeBinary --version).TrimStart('v').Split('.')[0]) -lt 22) { throw 'Node.js 22 or above is required' }
$mavenBinary = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if (-not $mavenBinary) {
    $mavenBinary = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $mavenBinary) { $mavenBinary = Join-Path $projectDirectory 'mvnw.cmd' }

function Invoke-Build([scriptblock]$Action) {
    $global:LASTEXITCODE = 0
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "Build command failed with exit code $LASTEXITCODE" }
}
function Reset-BuildDirectory([string]$RelativePath) {
    $targetDirectory = [IO.Path]::GetFullPath((Join-Path $projectDirectory $RelativePath))
    if (-not $targetDirectory.StartsWith($projectDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build directory must stay inside this project' }
    if (Test-Path -LiteralPath $targetDirectory) { Remove-Item -LiteralPath $targetDirectory -Recurse -Force }
    New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
    return $targetDirectory
}

Push-Location $projectDirectory
try {
    Write-Host 'Building and testing the web app'
    Push-Location 'music-party-web'
    try { Invoke-Build { npm.cmd ci }; Invoke-Build { npm.cmd test }; Invoke-Build { npm.cmd run build } }
    finally { Pop-Location }

    $staticDirectory = Reset-BuildDirectory 'src/main/resources/static'
    Copy-Item -Path 'music-party-web/dist/*' -Destination $staticDirectory -Recurse -Force
    Invoke-Build { & $mavenBinary 'clean' 'package' }

    Write-Host 'Preparing API Enhanced and the matching Node runtime'
    Push-Location $apiDirectory
    try { Invoke-Build { npm.cmd install --omit=dev --ignore-scripts } }
    finally { Pop-Location }
    if ($apiDirectory.StartsWith((Join-Path $projectDirectory 'launcher/bin'), [StringComparison]::OrdinalIgnoreCase)) { throw 'API source must be outside launcher/bin' }
    $apiTarget = Reset-BuildDirectory 'launcher/bin/netease-api'
    foreach ($name in @('app.js','main.js','server.js','generateConfig.js','package.json','LICENSE','module','util','plugins','public','data','node_modules')) {
        $source = Join-Path $apiDirectory $name
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $apiTarget -Recurse -Force }
    }
    Copy-Item -LiteralPath $nodeBinary -Destination 'launcher/bin/node.exe' -Force
    $nodeLicense = Join-Path (Split-Path $nodeBinary) 'LICENSE'
    if (Test-Path -LiteralPath $nodeLicense) { Copy-Item -LiteralPath $nodeLicense -Destination 'launcher/bin/NODE-LICENSE' -Force }
    $serverJar = Get-ChildItem -LiteralPath 'target' -Filter 'MusicParty-*.jar' | Select-Object -First 1
    if (-not $serverJar) { throw 'Server JAR not found' }
    Copy-Item -LiteralPath $serverJar.FullName -Destination 'launcher/bin/server.jar' -Force
    Copy-Item -LiteralPath $ffmpegBinary -Destination 'launcher/bin/ffmpeg.exe' -Force
    $jreDirectory = Reset-BuildDirectory 'launcher/bin/jre'
    Invoke-Build { jlink --add-modules 'java.base,java.logging,java.naming,java.desktop,java.management,java.security.jgss,java.instrument,java.sql,jdk.unsupported,java.net.http,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki' --strip-debug --no-man-pages --no-header-files --compress=2 --output (Join-Path $jreDirectory 'runtime') }
    Get-ChildItem -LiteralPath (Join-Path $jreDirectory 'runtime') | Move-Item -Destination $jreDirectory
    Remove-Item -LiteralPath (Join-Path $jreDirectory 'runtime')

    Write-Host 'Building Windows launcher'
    Push-Location 'launcher'
    try { Invoke-Build { wails build -platform windows/amd64 } }
    finally { Pop-Location }
    Write-Host 'Created launcher/build/bin/MusicParty.exe'
} finally { Pop-Location }
