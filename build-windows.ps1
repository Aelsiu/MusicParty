#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$NeteaseApiPath = (Join-Path $PSScriptRoot 'external-api-source'),
    [string]$FfmpegPath,
    [switch]$CheckEnvironment
)

$ErrorActionPreference = 'Stop'
$projectDirectory = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
. (Join-Path $projectDirectory 'scripts/build-metadata.ps1')

function Invoke-WindowsBuildCommand([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($Arguments -join ' ')" }
}

function Get-WindowsBuildTool([string]$Name, [string]$Hint) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) { throw "Missing $Name. $Hint" }
    return $command.Source
}

function Get-WindowsGeneratedPath([string]$RelativePath) {
    $target = [IO.Path]::GetFullPath((Join-Path $projectDirectory $RelativePath))
    if (-not $target.StartsWith($projectDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Generated path must stay inside this project'
    }
    $candidate = $target
    while ($candidate -ne $projectDirectory) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Generated path contains a junction or symlink: $candidate" }
        }
        $candidate = Split-Path -Parent $candidate
    }
    return $target
}

function Reset-WindowsBuildDirectory([string]$RelativePath) {
    $target = Get-WindowsGeneratedPath $RelativePath
    if (Test-Path -LiteralPath $target) {
        if (-not (Test-Path -LiteralPath $target -PathType Container)) { throw "Expected a generated directory: $target" }
        $linkedItem = Get-ChildItem -LiteralPath $target -Recurse -Force |
            Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 } | Select-Object -First 1
        if ($linkedItem) { throw "Generated directory contains a junction or symlink: $($linkedItem.FullName)" }
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    return $target
}

function Assert-WindowsAmd64Executable([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $reader = New-Object IO.BinaryReader($stream)
    try {
        if ($reader.ReadUInt16() -ne 0x5a4d) { throw "Expected a Windows executable: $Path" }
        $stream.Position = 0x3c
        $stream.Position = $reader.ReadInt32()
        if ($reader.ReadUInt32() -ne 0x00004550 -or $reader.ReadUInt16() -ne 0x8664) {
            throw "Expected a Windows x64 executable: $Path"
        }
    } finally { $reader.Dispose() }
}

$savedEnvironment = @{}
foreach ($name in @('GOTOOLCHAIN', 'GOFLAGS', 'JAVA_HOME', 'PATH', 'VITE_APP_VERSION')) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $projectDirectory
try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or -not [Environment]::Is64BitOperatingSystem) {
        throw 'build-windows.ps1 requires Windows 10/11 x64. Use build-ubuntu.ps1 for an Ubuntu deployment.'
    }
    if ([Environment]::OSVersion.Version.Major -lt 10) { throw 'Windows 10 or above is required' }
    $gitBinary = Get-WindowsBuildTool 'git.exe' 'Install Git 2.x or above from https://git-scm.com/downloads and reopen PowerShell.'
    $gitVersion = (Invoke-WindowsBuildCommand $gitBinary @('--version') | Out-String).Trim()
    if ($gitVersion -notmatch '^git version (\d+)\.' -or [int]$Matches[1] -lt 2) { throw "Git 2.x or above is required. Found: $gitVersion" }
    $metadata = Get-MusicPartyBuildMetadata $projectDirectory $gitBinary
    $artifactName = Get-MusicPartyArtifactName 'windows' $metadata

    # Use the installed Go version, never download or force a particular toolchain.
    $env:GOTOOLCHAIN = 'local'
    $goBinary = Get-WindowsBuildTool 'go.exe' 'Install Go 1.23.12 or a newer stable version from https://go.dev/dl/ and reopen PowerShell.'
    $goDescription = (Invoke-WindowsBuildCommand $goBinary @('version') | Out-String).Trim()
    if ($goDescription -notmatch '^go version go(\d+\.\d+(?:\.\d+)?) windows/amd64$' -or [version]$Matches[1] -lt [version]'1.23.12') {
        throw "Go 1.23.12 or above (stable Windows amd64) is required. Found: $goDescription"
    }
    $nodeBinary = Get-WindowsBuildTool 'node.exe' 'Install Node.js 22.12.0 or above from https://nodejs.org/en/download.'
    $npmBinary = Get-WindowsBuildTool 'npm.cmd' 'Install the npm component included with Node.js.'
    $nodeVersion = (Invoke-WindowsBuildCommand $nodeBinary @('--version') | Out-String).Trim()
    if ($nodeVersion -notmatch '^v(\d+\.\d+\.\d+)$' -or [version]$Matches[1] -lt [version]'22.12.0') {
        throw "Node.js 22.12.0 or above is required by the web app's Vite 7 dependency. Found: $nodeVersion"
    }
    $nodeArchitecture = (Invoke-WindowsBuildCommand $nodeBinary @('-p', 'process.arch') | Out-String).Trim()
    if ($nodeArchitecture -ne 'x64') { throw "Install the Windows x64 Node.js runtime. Found: $nodeArchitecture" }
    $npmVersion = (Invoke-WindowsBuildCommand $npmBinary @('--version') | Out-String).Trim()
    if ($npmVersion -notmatch '^(\d+)\.' -or [int]$Matches[1] -lt 7) { throw "npm 7 or above is required for package-lock.json. Found: $npmVersion" }

    $javaBinary = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { Get-WindowsBuildTool 'java.exe' 'Install a Windows x64 JDK 21, set JAVA_HOME to its directory and reopen PowerShell.' }
    if (-not (Test-Path -LiteralPath $javaBinary -PathType Leaf)) { throw "JAVA_HOME does not contain bin/java.exe: $env:JAVA_HOME" }
    # Windows PowerShell 5.1 represents native stderr as ErrorRecords even when redirected.
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaSettings = & $javaBinary '-XshowSettings:properties' '-version' 2>&1 | Out-String
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the Java installation' }
    if ($javaSettings -notmatch '(?m)^\s*java\.version\s*=\s*21[.\s]' -or $javaSettings -notmatch '(?m)^\s*sun\.arch\.data\.model\s*=\s*64\s*$') {
        throw 'A Windows x64 JDK 21 is required (not a JRE or a newer JDK). Set JAVA_HOME to JDK 21.'
    }
    if ($javaSettings -notmatch '(?m)^\s*java\.home\s*=\s*(.+)$') { throw 'Could not determine java.home; set JAVA_HOME to JDK 21' }
    $jdkDirectory = $Matches[1].Trim()
    foreach ($name in @('javac.exe', 'jlink.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $jdkDirectory "bin/$name") -PathType Leaf)) { throw "JDK 21 is missing bin/$name; install the complete JDK" }
    }
    $env:JAVA_HOME = $jdkDirectory
    $env:PATH = (Join-Path $jdkDirectory 'bin') + ';' + (Split-Path -Parent $nodeBinary) + ';' + (Split-Path -Parent $goBinary) + ';' + $env:PATH

    $wailsBinary = Get-WindowsBuildTool 'wails.exe' 'Run go install github.com/wailsapp/wails/v2/cmd/wails@v2.12.0 and add the Go bin directory to PATH.'
    $wailsVersion = Invoke-WindowsBuildCommand $wailsBinary @('version') | Out-String
    if ($wailsVersion -notmatch '(?m)^v2\.12\.0\s*$') {
        throw 'Wails CLI 2.12.0 must match launcher/go.mod. Install it with: go install github.com/wailsapp/wails/v2/cmd/wails@v2.12.0'
    }
    $mavenBinary = Join-Path $projectDirectory 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenBinary -PathType Leaf)) { throw 'mvnw.cmd is missing. Restore the Maven wrapper from this repository.' }

    $ffmpegBinary = $null
    if ($FfmpegPath) {
        $ffmpegBinary = (Resolve-Path -LiteralPath $FfmpegPath).Path
        if (-not (Test-Path -LiteralPath $ffmpegBinary -PathType Leaf)) { throw 'FfmpegPath must point to ffmpeg.exe' }
        Assert-WindowsAmd64Executable $ffmpegBinary
        $ffmpegDescription = Invoke-WindowsBuildCommand $ffmpegBinary @('-version') | Out-String
        if ($ffmpegDescription -notmatch '^ffmpeg version (?:n)?(\d+\.\d+(?:\.\d+)?)' -or [version]$Matches[1] -lt [version]'7.0.1') {
            throw 'Use a Windows x64 FFmpeg 7.0.1 or above static build from https://ffmpeg.org/download.html#build-windows'
        }
    }
    Write-Host "Environment ready: $goDescription; Node $nodeVersion; npm $npmVersion; JDK 21 x64; Wails 2.12.0. Maven wrapper downloads Maven 3.9.12 when needed. Version: $($metadata.Version); commit: $($metadata.ShortCommit)."
    if ($CheckEnvironment) { return }
    if (-not $ffmpegBinary) { throw 'FfmpegPath is required. Example: .\build-windows.ps1 -FfmpegPath C:\tools\ffmpeg\bin\ffmpeg.exe' }
    $apiDirectory = (Resolve-Path -LiteralPath $NeteaseApiPath).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
    foreach ($name in @('app.js', 'package.json', 'module', 'util')) {
        if (-not (Test-Path -LiteralPath (Join-Path $apiDirectory $name))) { throw "NeteaseApiPath must contain API Enhanced source ($name is missing). See BUILD.md." }
    }
    $assetDirectory = Get-WindowsGeneratedPath 'launcher/bin'
    foreach ($sourcePath in @($apiDirectory, $ffmpegBinary, $nodeBinary, $jdkDirectory)) {
        if ($sourcePath -eq $assetDirectory -or $sourcePath.StartsWith($assetDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'API source and runtime tools must be outside launcher/bin, which is replaced during the build'
        }
    }

    Write-Host "Building $artifactName"
    $env:VITE_APP_VERSION = $metadata.Version
    Write-Host 'Building and testing the web app'
    Push-Location 'music-party-web'
    try {
        Invoke-WindowsBuildCommand $npmBinary @('ci')
        Invoke-WindowsBuildCommand $npmBinary @('test')
        Invoke-WindowsBuildCommand $npmBinary @('run', 'build')
    } finally { Pop-Location }
    $staticDirectory = Reset-WindowsBuildDirectory 'src/main/resources/static'
    Get-ChildItem -LiteralPath 'music-party-web/dist' -Force | Copy-Item -Destination $staticDirectory -Recurse -Force
    # Remove generated output ourselves so junctions are checked before recursive deletion.
    Reset-WindowsBuildDirectory 'target' | Out-Null
    Invoke-WindowsBuildCommand $mavenBinary @('package', "-Drevision=$($metadata.Version)")

    Write-Host 'Preparing API Enhanced and the matching Node runtime'
    Reset-WindowsBuildDirectory 'launcher/bin' | Out-Null
    $apiTarget = Reset-WindowsBuildDirectory 'launcher/bin/netease-api'
    foreach ($name in @('app.js', 'main.js', 'server.js', 'generateConfig.js', 'package.json', 'package-lock.json', 'LICENSE', 'module', 'util', 'plugins', 'public', 'data')) {
        $source = Join-Path $apiDirectory $name
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $apiTarget -Recurse -Force }
    }
    # Install only in the generated copy; leave the external source checkout untouched.
    Push-Location $apiTarget
    try {
        if (Test-Path -LiteralPath 'package-lock.json') { Invoke-WindowsBuildCommand $npmBinary @('ci', '--omit=dev', '--ignore-scripts') }
        else { Invoke-WindowsBuildCommand $npmBinary @('install', '--omit=dev', '--ignore-scripts') }
    } finally { Pop-Location }
    Copy-Item -LiteralPath $nodeBinary -Destination (Join-Path $assetDirectory 'node.exe') -Force
    $nodeLicense = Join-Path (Split-Path -Parent $nodeBinary) 'LICENSE'
    if (Test-Path -LiteralPath $nodeLicense) { Copy-Item -LiteralPath $nodeLicense -Destination (Join-Path $assetDirectory 'NODE-LICENSE') -Force }
    $serverJar = Get-WindowsGeneratedPath "target/MusicParty-$($metadata.Version).jar"
    if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) { throw "Expected application JAR was not created: $serverJar" }
    Copy-Item -LiteralPath $serverJar -Destination (Join-Path $assetDirectory 'server.jar') -Force
    Copy-Item -LiteralPath $ffmpegBinary -Destination (Join-Path $assetDirectory 'ffmpeg.exe') -Force
    $jreDirectory = Join-Path $assetDirectory 'jre'
    Invoke-WindowsBuildCommand (Join-Path $jdkDirectory 'bin/jlink.exe') @('--add-modules', 'java.base,java.logging,java.naming,java.desktop,java.management,java.security.jgss,java.instrument,java.sql,jdk.unsupported,java.net.http,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki', '--strip-debug', '--no-man-pages', '--no-header-files', '--compress=2', '--output', $jreDirectory)

    Write-Host 'Building Windows launcher'
    $env:GOFLAGS = ($env:GOFLAGS + ' -mod=readonly').Trim()
    $launcherOutput = Get-WindowsGeneratedPath "launcher/build/bin/$artifactName.exe"
    Push-Location 'launcher'
    try { Invoke-WindowsBuildCommand $wailsBinary @('build', '-platform', 'windows/amd64', '-m', '-o', "$artifactName.exe") }
    finally { Pop-Location }
    if (-not (Test-Path -LiteralPath $launcherOutput -PathType Leaf)) { throw "Wails did not create $launcherOutput" }
    Assert-MusicPartyBuildMetadata $projectDirectory $gitBinary $metadata
    $outputDirectory = Get-WindowsGeneratedPath 'dist/windows'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $outputFile = Get-WindowsGeneratedPath "dist/windows/$artifactName.exe"
    $checksumFile = Get-WindowsGeneratedPath "dist/windows/$artifactName.exe.sha256"
    Copy-Item -LiteralPath $launcherOutput -Destination $outputFile -Force
    $checksum = (Get-FileHash -LiteralPath $outputFile -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($checksumFile, "$checksum  $artifactName.exe`n", [Text.Encoding]::ASCII)
    Write-Host "Created $outputFile"
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    }
    Pop-Location
}
