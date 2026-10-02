#Requires -Version 7.0
[CmdletBinding()]
param(
    # Retained for compatibility; npm ci now runs on every build.
    [switch]$InstallDependencies,
    [switch]$Offline,
    [switch]$CheckEnvironment
)
. (Join-Path $PSScriptRoot 'scripts/build-common.ps1')
$gitBinary = Get-BuildTool @('git.exe', 'git')
$nodeBinary = Get-BuildTool @('node.exe', 'node')
$npmBinary = Get-BuildTool @('npm.cmd', 'npm')
$tarBinary = Get-BuildTool @('tar.exe', 'tar')
$maven = Get-BuildMaven -Offline:$Offline
$previousVersion = $env:VITE_APP_VERSION
Push-Location $buildProjectDirectory
try {
    $nodeVersion = (Invoke-BuildCommand $nodeBinary @('--version')).Trim()
    if ($nodeVersion -notmatch '^v(\d+\.\d+\.\d+)$' -or [version]$Matches[1] -lt [version]'22.12.0') {
        throw 'Node.js 22.12.0 or above is required by the web build. See BUILD.md.'
    }
    $npmVersion = (Invoke-BuildCommand $npmBinary @('--version')).Trim()
    if ($npmVersion -notmatch '^(\d+)\.' -or [int]$Matches[1] -lt 7) { throw 'npm 7 or above is required. Use the npm bundled with Node.js.' }
    Test-BuildJavaAndMaven $maven
    $commit = Get-BuildCommit $gitBinary
    foreach ($file in @('Dockerfile.incremental', 'scripts/update-docker.sh', 'docs/incremental-update.md')) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing update package source: $file" }
    }
    if ($CheckEnvironment) { Write-Host "Update build environment ready. Version: $($commit.Version); HEAD: $($commit.Commit)"; return }
    Write-Host "Building Ubuntu application update $($commit.Version) for $($commit.Commit)"
    $env:VITE_APP_VERSION = $commit.Version
    Push-Location 'music-party-web'
    try {
        $npmArguments = @('ci')
        if ($Offline) { $npmArguments += '--offline' }
        Invoke-BuildCommand $npmBinary $npmArguments
        Invoke-BuildCommand $npmBinary @('test')
        Invoke-BuildCommand $npmBinary @('run', 'build')
    } finally { Pop-Location }
    $staticDirectory = Reset-BuildDirectory 'src/main/resources/static'
    Get-ChildItem -LiteralPath 'music-party-web/dist' -Force | Copy-Item -Destination $staticDirectory -Recurse -Force
    # Remove compiled output to prevent classes deleted in HEAD leaking into this JAR.
    foreach ($path in @('target/classes', 'target/test-classes')) {
        if (Test-Path -LiteralPath (Get-BuildPath $path)) { Reset-BuildDirectory $path | Out-Null }
    }
    $mavenArguments = $maven.Prefix + @('package', "-Drevision=$($commit.Version)")
    if ($Offline) { $mavenArguments += '-o' }
    Invoke-BuildCommand $maven.Executable $mavenArguments
    $serverJar = Get-BuildPath "target/MusicParty-$($commit.Version).jar"
    if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) { throw "Expected application JAR was not created: $serverJar" }
    $afterBuild = Get-BuildCommit $gitBinary
    if ($afterBuild.Commit -ne $commit.Commit -or $afterBuild.Version -ne $commit.Version) { throw 'HEAD or project VERSION changed during the build. Run the build again.' }
    $bundleName = Get-MusicPartyArtifactName 'update' $commit
    $bundleDirectory = Reset-BuildDirectory "dist/update/$bundleName"
    Copy-Item -LiteralPath $serverJar -Destination (Join-Path $bundleDirectory 'app.jar')
    Copy-Item -LiteralPath 'Dockerfile.incremental' -Destination $bundleDirectory
    Write-BuildText (Join-Path $bundleDirectory 'update-docker.sh') ([IO.File]::ReadAllText((Join-Path $buildProjectDirectory 'scripts/update-docker.sh')))
    Copy-Item -LiteralPath 'docs/incremental-update.md' -Destination (Join-Path $bundleDirectory 'README.md')
    Write-BuildText (Join-Path $bundleDirectory 'VERSION') "$($commit.Commit)`n"
    Write-BuildText (Join-Path $bundleDirectory 'APP_VERSION') "$($commit.Version)`n"
    Write-BuildChecksums $bundleDirectory @('app.jar', 'Dockerfile.incremental', 'update-docker.sh', 'VERSION', 'APP_VERSION', 'README.md')
    $archivePath = Get-BuildPath "dist/update/$bundleName.tar.gz"
    Invoke-BuildCommand $tarBinary @('-czf', $archivePath, '-C', (Join-Path $buildProjectDirectory 'dist/update'), $bundleName)
    Write-Host "Created update archive: $archivePath"
} finally {
    $env:VITE_APP_VERSION = $previousVersion
    Pop-Location
}
