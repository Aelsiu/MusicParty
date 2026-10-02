# Shared by the Ubuntu deployment and update builders (PowerShell 7+).
$ErrorActionPreference = 'Stop'
$buildProjectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd([IO.Path]::DirectorySeparatorChar)
. (Join-Path $PSScriptRoot 'build-metadata.ps1')

function Get-BuildTool([string[]]$Names) {
    foreach ($name in $Names) {
        $command = Get-Command $name -CommandType Application, ExternalScript -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { return $command.Source }
    }
    throw "Required tool not found: $($Names -join ' / '). See BUILD.md for installation instructions."
}

function Invoke-BuildCommand([string]$Executable, [string[]]$Arguments) {
    $global:LASTEXITCODE = 0
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($Arguments -join ' ')" }
}

function Get-BuildPath([string]$RelativePath) {
    $target = [IO.Path]::GetFullPath((Join-Path $buildProjectDirectory $RelativePath))
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if (-not $target.StartsWith($buildProjectDirectory + [IO.Path]::DirectorySeparatorChar, $comparison)) { throw 'Generated path must stay inside this project' }
    $candidate = $target
    while ($candidate -ne $buildProjectDirectory) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Generated path contains a junction or symlink: $candidate" }
        }
        $candidate = Split-Path -Parent $candidate
    }
    return $target
}

function Reset-BuildDirectory([string]$RelativePath) {
    $target = Get-BuildPath $RelativePath
    if (Test-Path -LiteralPath $target) {
        if (-not (Test-Path -LiteralPath $target -PathType Container)) { throw "Expected a directory: $target" }
        $link = Get-ChildItem -LiteralPath $target -Recurse -Force | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 } | Select-Object -First 1
        if ($link) { throw "Generated directory contains a junction or symlink: $($link.FullName)" }
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    return $target
}

function Write-BuildText([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
}

function Write-BuildChecksums([string]$Directory, [string[]]$Files) {
    $lines = foreach ($file in $Files) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $Directory $file) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $file"
    }
    Write-BuildText (Join-Path $Directory 'SHA256SUMS') (($lines -join "`n") + "`n")
}

function Get-BuildCommit([string]$GitBinary) {
    $metadata = Get-MusicPartyBuildMetadata $buildProjectDirectory $GitBinary
    $dirty = @(Invoke-BuildCommand $GitBinary @('-C', $buildProjectDirectory, 'status', '--porcelain=v1', '--untracked-files=all', '--', 'pom.xml', 'src', 'music-party-web'))
    if ($dirty.Count -gt 0) { throw "Application source or dependency definitions have uncommitted changes. Commit them before labeling a package as $($metadata.ShortCommit):`n$($dirty -join "`n")" }
    return $metadata
}

function Get-BuildMaven {
    param([switch]$Offline)
    $maven = Get-Command $(if ($IsWindows) { 'mvn.cmd' } else { 'mvn' }) -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($maven) { return @{ Executable = $maven.Source; Prefix = @() } }
    $distribution = Get-Content -LiteralPath (Join-Path $buildProjectDirectory '.mvn/wrapper/maven-wrapper.properties') | Where-Object { $_ -match '^distributionUrl=' } | Select-Object -First 1
    if ($distribution -match 'apache-maven-([0-9.]+)-bin') {
        $userDirectory = if ($IsWindows) { $env:USERPROFILE } else { $env:HOME }
        $mavenUserDirectory = if ($env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME } else { Join-Path $userDirectory '.m2' }
        $cacheRoot = Join-Path $mavenUserDirectory "wrapper/dists/apache-maven-$($Matches[1])"
        $cached = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter $(if ($IsWindows) { 'mvn.cmd' } else { 'mvn' }) -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cached) { return @{ Executable = $cached.FullName; Prefix = @() } }
    }
    if ($Offline) { throw 'Maven is not installed and the wrapper distribution is not cached. Prepare Maven online before using -Offline.' }
    if ($IsWindows) { return @{ Executable = Join-Path $buildProjectDirectory 'mvnw.cmd'; Prefix = @() } }
    return @{ Executable = Get-BuildTool @('sh'); Prefix = @(Join-Path $buildProjectDirectory 'mvnw') }
}

function Test-BuildJavaAndMaven([hashtable]$Maven) {
    $javac = Get-BuildTool @('javac.exe', 'javac')
    $javaVersion = (Invoke-BuildCommand $javac @('-version') | Out-String).Trim()
    if ($javaVersion -notmatch '^javac 21(?:[.\s]|$)') { throw "JDK 21 is required for the server build; found $javaVersion. Check JAVA_HOME and PATH." }
    $mavenInfo = (Invoke-BuildCommand $Maven.Executable ($Maven.Prefix + @('-version')) | Out-String)
    if ($mavenInfo -notmatch 'Apache Maven (\d+\.\d+\.\d+)' -or [version]$Matches[1] -lt [version]'3.6.3') { throw 'Maven 3.6.3 or above is required. See BUILD.md.' }
    if ($mavenInfo -notmatch 'Java version: 21(?:[.,\s]|$)') { throw 'Maven must run on JDK 21. Check JAVA_HOME (it takes precedence over PATH).' }
}
