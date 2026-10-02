#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('linux/amd64', 'linux/arm64')][string]$Platform = 'linux/amd64',
    [string]$NeteaseApiImage = 'moefurina/ncm-api:latest',
    [switch]$CheckEnvironment
)
. (Join-Path $PSScriptRoot 'scripts/build-common.ps1')
$gitBinary = Get-BuildTool @('git.exe', 'git')
$dockerBinary = Get-BuildTool @('docker.exe', 'docker')
$tarBinary = Get-BuildTool @('tar.exe', 'tar')
if ($NeteaseApiImage -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$') { throw 'Invalid Netease API image reference' }
Push-Location $buildProjectDirectory
try {
    $dockerVersion = (Invoke-BuildCommand $dockerBinary @('version', '--format', '{{.Server.Version}}')).Trim()
    if ($dockerVersion -notmatch '^(\d+)\.' -or [int]$Matches[1] -lt 24) { throw 'Docker Engine 24 or above is required. Start Docker and see BUILD.md.' }
    $dockerOS = (Invoke-BuildCommand $dockerBinary @('info', '--format', '{{.OSType}}')).Trim()
    if ($dockerOS -ne 'linux') { throw 'Docker must use Linux containers. Switch Docker Desktop to Linux containers.' }
    $commit = Get-BuildCommit $gitBinary
    foreach ($file in @('Dockerfile', 'docker-compose.yml', 'config/application.properties.example', 'scripts/deploy-docker.sh', 'docs/ubuntu-deployment.md')) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing deployment package source: $file" }
    }
    if ($CheckEnvironment) { Write-Host "Ubuntu build environment ready ($Platform). Version: $($commit.Version); HEAD: $($commit.Commit)"; return }
    $architecture = $Platform.Split('/')[1]
    $imageVersion = "$($commit.Version)-$($commit.ShortCommit)-$architecture"
    $appImage = "music-party-custom:$imageVersion"
    $apiImage = "music-party-netease:$imageVersion"
    Write-Host "Building Ubuntu $($commit.Version) for $($commit.Commit) ($Platform)"
    Invoke-BuildCommand $dockerBinary @('build', '--platform', $Platform, '--build-arg', "APP_VERSION=$($commit.Version)", '-t', $appImage, '-f', 'Dockerfile', '.')
    Invoke-BuildCommand $dockerBinary @('pull', '--platform', $Platform, $NeteaseApiImage)
    Invoke-BuildCommand $dockerBinary @('tag', $NeteaseApiImage, $apiImage)
    foreach ($image in @($appImage, $apiImage)) {
        $imagePlatform = (Invoke-BuildCommand $dockerBinary @('image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', $image)).Trim()
        if ($imagePlatform -ne $Platform) { throw "Image $image is $imagePlatform instead of $Platform. Use an API image that supports this architecture." }
    }
    $afterBuild = Get-BuildCommit $gitBinary
    if ($afterBuild.Commit -ne $commit.Commit -or $afterBuild.Version -ne $commit.Version) { throw 'HEAD or project VERSION changed during the build. Run the build again.' }
    $bundleName = Get-MusicPartyArtifactName 'ubuntu' $commit
    $outputDirectory = "dist/ubuntu/$architecture"
    $bundleDirectory = Reset-BuildDirectory "$outputDirectory/$bundleName"
    Invoke-BuildCommand $dockerBinary @('save', '-o', (Join-Path $bundleDirectory 'images.tar'), $appImage, $apiImage)
    # Keep the standard local application tag compatible with update-docker.sh.
    $compose = [IO.File]::ReadAllText((Join-Path $buildProjectDirectory 'docker-compose.yml'))
    $compose = $compose.Replace('    build: .', '').Replace('moefurina/ncm-api:latest', $apiImage)
    Write-BuildText (Join-Path $bundleDirectory 'docker-compose.yml') $compose
    $configDirectory = Join-Path $bundleDirectory 'config'
    New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
    Copy-Item -LiteralPath 'config/application.properties.example' -Destination $configDirectory
    Write-BuildText (Join-Path $bundleDirectory 'deploy-docker.sh') ([IO.File]::ReadAllText((Join-Path $buildProjectDirectory 'scripts/deploy-docker.sh')))
    Copy-Item -LiteralPath 'docs/ubuntu-deployment.md' -Destination (Join-Path $bundleDirectory 'README.md')
    Write-BuildText (Join-Path $bundleDirectory 'APP_IMAGE') "$appImage`n"
    Write-BuildText (Join-Path $bundleDirectory 'PLATFORM') "$Platform`n"
    Write-BuildText (Join-Path $bundleDirectory 'VERSION') "$($commit.Commit)`n"
    Write-BuildText (Join-Path $bundleDirectory 'APP_VERSION') "$($commit.Version)`n"
    $apiId = (Invoke-BuildCommand $dockerBinary @('image', 'inspect', '--format', '{{.Id}}', $apiImage)).Trim()
    Write-BuildText (Join-Path $bundleDirectory 'API_IMAGE') "$NeteaseApiImage`n$apiId`n"
    Write-BuildChecksums $bundleDirectory @('images.tar', 'docker-compose.yml', 'config/application.properties.example', 'deploy-docker.sh', 'APP_IMAGE', 'PLATFORM', 'VERSION', 'APP_VERSION', 'API_IMAGE', 'README.md')
    $archivePath = Get-BuildPath "$outputDirectory/$bundleName.tar.gz"
    Invoke-BuildCommand $tarBinary @('-czf', $archivePath, '-C', (Join-Path $buildProjectDirectory $outputDirectory), $bundleName)
    Write-Host "Created Ubuntu deployment archive: $archivePath"
}
finally { Pop-Location }
