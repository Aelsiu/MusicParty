#requires -Version 7.0
<# Ubuntu packaging tool-contract tests; no Docker daemon or Git checkout is required. #>
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $repository ('build/ubuntu-script-test-' + [guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in @('MP_UBUNTU_TEST_ROOT', 'MP_UBUNTU_TEST_VERSION', 'MP_UBUNTU_TEST_OS', 'MP_UBUNTU_TEST_PLATFORM',
        'MP_UBUNTU_TEST_FAIL', 'MP_UBUNTU_TEST_DIRTY', 'MP_UBUNTU_TEST_CHANGE_HEAD', 'MP_UBUNTU_TEST_CHANGE_VERSION')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}
function Assert-Fails([scriptblock]$Action, [string]$Pattern) {
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw }
        return
    }
    throw "Expected failure matching: $Pattern"
}
function Write-Fixture([string]$RelativePath, [string]$Content) {
    $path = Join-Path $testRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}
function Clear-TestState {
    foreach ($name in @('MP_UBUNTU_TEST_VERSION', 'MP_UBUNTU_TEST_OS', 'MP_UBUNTU_TEST_PLATFORM',
            'MP_UBUNTU_TEST_FAIL', 'MP_UBUNTU_TEST_DIRTY', 'MP_UBUNTU_TEST_CHANGE_HEAD', 'MP_UBUNTU_TEST_CHANGE_VERSION')) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
    Write-Fixture 'VERSION' "1.3.9`n"
    foreach ($file in @('tools.jsonl', 'head-changed')) { Remove-Item -LiteralPath (Join-Path $testRoot $file) -ErrorAction SilentlyContinue }
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repository 'build-ubuntu.ps1') -Destination $testRoot
    foreach ($file in @('VERSION', 'Dockerfile', 'docker-compose.yml', '.dockerignore', 'config/application.properties.example', 'scripts/build-metadata.ps1', 'scripts/deploy-docker.sh', 'docs/ubuntu-deployment.md')) {
        $destination = Join-Path $testRoot $file
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repository $file) -Destination $destination
    }
    $common = [IO.File]::ReadAllText((Join-Path $repository 'scripts/build-common.ps1'))
    # Replace only executable discovery; exercise the real build, command checking and packaging code.
    $common += @'

function Get-BuildTool([string[]]$Names) {
    $name = [IO.Path]::GetFileNameWithoutExtension($Names[0])
    return Join-Path $env:MP_UBUNTU_TEST_ROOT "tools/$name.ps1"
}
'@
    Write-Fixture 'scripts/build-common.ps1' $common
    Write-Fixture 'config/application.properties' 'PRIVATE_ROOT_KEY_DO_NOT_PACKAGE'
    Write-Fixture 'config/licenses.json' 'PRIVATE_LICENSES_DO_NOT_PACKAGE'
    Write-Fixture 'music_party/data/multi-rooms.sqlite' 'PRIVATE_DATABASE_DO_NOT_PACKAGE'
    Write-Fixture 'tools/git.ps1' @'
Add-Content -LiteralPath (Join-Path $env:MP_UBUNTU_TEST_ROOT 'tools.jsonl') -Value (@{Tool='git';Arguments=@($args)} | ConvertTo-Json -Compress)
switch ($args[2]) {
    'rev-parse' {
        $changed = Test-Path -LiteralPath (Join-Path $env:MP_UBUNTU_TEST_ROOT 'head-changed')
        $sha = if ($changed) { 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' } else { 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }
        if ($args -contains '--short=7') { Write-Output $sha.Substring(0, 7) } else { Write-Output $sha }
    }
    'status' { if ($env:MP_UBUNTU_TEST_DIRTY) { Write-Output ' M src/main/java/Example.java' } }
    default { throw "Unexpected Git invocation: $args" }
}
exit 0
'@
    Write-Fixture 'tools/docker.ps1' @'
Add-Content -LiteralPath (Join-Path $env:MP_UBUNTU_TEST_ROOT 'tools.jsonl') -Value (@{Tool='docker';Arguments=@($args)} | ConvertTo-Json -Compress)
if ($env:MP_UBUNTU_TEST_FAIL -eq $args[0]) { exit 19 }
switch ($args[0]) {
    'version' { if ($env:MP_UBUNTU_TEST_VERSION) { $env:MP_UBUNTU_TEST_VERSION } else { '24.0.9' } }
    'info' { if ($env:MP_UBUNTU_TEST_OS) { $env:MP_UBUNTU_TEST_OS } else { 'linux' } }
    'build' {
        if ($env:MP_UBUNTU_TEST_CHANGE_HEAD) { [IO.File]::WriteAllText((Join-Path $env:MP_UBUNTU_TEST_ROOT 'head-changed'), 'changed') }
        if ($env:MP_UBUNTU_TEST_CHANGE_VERSION) { [IO.File]::WriteAllText((Join-Path $env:MP_UBUNTU_TEST_ROOT 'VERSION'), '1.4.0') }
    }
    'pull' { }
    'tag' { }
    'image' {
        if ($args -contains '{{.Id}}') { 'sha256:1234567890abcdef' }
        elseif ($env:MP_UBUNTU_TEST_PLATFORM) { $env:MP_UBUNTU_TEST_PLATFORM }
        else { 'linux/amd64' }
    }
    'save' {
        $index = [array]::IndexOf($args, '-o')
        if ($index -lt 0) { throw 'docker save must use -o' }
        [IO.File]::WriteAllText($args[$index + 1], 'fixture image archive')
    }
    default { throw "Unexpected Docker invocation: $args" }
}
exit 0
'@
    Write-Fixture 'tools/tar.ps1' @'
Add-Content -LiteralPath (Join-Path $env:MP_UBUNTU_TEST_ROOT 'tools.jsonl') -Value (@{Tool='tar';Arguments=@($args)} | ConvertTo-Json -Compress)
if ($args[0] -ne '-czf' -or $args[2] -ne '-C') { throw 'Unexpected tar archive arguments' }
[IO.File]::WriteAllText($args[1], 'fixture deployment archive')
exit 0
'@
    $env:MP_UBUNTU_TEST_ROOT = $testRoot
    Clear-TestState
    $script = Join-Path $testRoot 'build-ubuntu.ps1'
    $startLocation = (Get-Location).Path

    & $script -CheckEnvironment
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist'))) 'Preflight must not create a package directory'
    $calls = @(Get-Content -LiteralPath (Join-Path $testRoot 'tools.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    Assert-True (@($calls | Where-Object { $_.Tool -eq 'docker' -and $_.Arguments[0] -in @('build', 'pull', 'save') }).Count -eq 0) 'Preflight must not build or pull images'
    Assert-True ((Get-Location).Path -eq $startLocation) 'Preflight must restore the working directory'

    Clear-TestState
    $env:MP_UBUNTU_TEST_VERSION = '23.0.6'
    Assert-Fails { & $script -CheckEnvironment } 'Docker Engine 24'
    Clear-TestState
    $env:MP_UBUNTU_TEST_OS = 'windows'
    Assert-Fails { & $script -CheckEnvironment } 'Linux containers'
    Clear-TestState
    $env:MP_UBUNTU_TEST_DIRTY = '1'
    Assert-Fails { & $script -CheckEnvironment } 'uncommitted changes'
    Clear-TestState
    $env:MP_UBUNTU_TEST_FAIL = 'build'
    Assert-Fails { & $script } 'exit code 19'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist'))) 'Failed Docker build must not create a package'
    Clear-TestState
    $env:MP_UBUNTU_TEST_PLATFORM = 'linux/arm64'
    Assert-Fails { & $script } 'instead of linux/amd64'
    Clear-TestState
    $env:MP_UBUNTU_TEST_CHANGE_HEAD = '1'
    Assert-Fails { & $script } 'HEAD.*changed'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist'))) 'Changed HEAD must not publish a mislabeled package'
    Clear-TestState
    $env:MP_UBUNTU_TEST_CHANGE_VERSION = '1'
    Assert-Fails { & $script } 'VERSION changed'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist'))) 'Changed version must not publish a mislabeled package'

    Clear-TestState
    & $script -NeteaseApiImage 'registry.example/ncm-api:v1'
    $bundleName = 'MusicParty-ubuntu-1.3.9-aaaaaaa'
    $bundle = Join-Path $testRoot "dist/ubuntu/amd64/$bundleName"
    Assert-True (Test-Path -LiteralPath (Join-Path $testRoot "dist/ubuntu/amd64/$bundleName.tar.gz")) 'Deployment archive must use the shared version and HEAD filename'
    $calls = @(Get-Content -LiteralPath (Join-Path $testRoot 'tools.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    $build = $calls | Where-Object { $_.Tool -eq 'docker' -and $_.Arguments[0] -eq 'build' } | Select-Object -First 1
    Assert-True ($build.Arguments -contains '--platform' -and $build.Arguments -contains 'linux/amd64' -and $build.Arguments -contains 'APP_VERSION=1.3.9') 'Image build must use the requested architecture and shared project version'
    $pull = $calls | Where-Object { $_.Tool -eq 'docker' -and $_.Arguments[0] -eq 'pull' } | Select-Object -First 1
    Assert-True ($pull.Arguments -contains 'registry.example/ncm-api:v1') 'API image override must reach Docker'
    $compose = Get-Content -LiteralPath (Join-Path $bundle 'docker-compose.yml') -Raw
    Assert-True ($compose -notmatch 'build: \.' -and $compose -match 'image: music-party-custom:local' -and $compose -match 'image: music-party-netease:1.3.9-aaaaaaa-amd64') 'Packaged Compose must use loaded images and remain compatible with incremental updates'
    Assert-True ((Get-Content -LiteralPath (Join-Path $bundle 'VERSION') -Raw).Trim() -eq ('a' * 40)) 'VERSION must record the full HEAD commit'
    Assert-True ((Get-Content -LiteralPath (Join-Path $bundle 'APP_VERSION') -Raw).Trim() -eq '1.3.9') 'APP_VERSION must record the shared project version'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $bundle 'config/application.properties')) -and -not (Test-Path -LiteralPath (Join-Path $bundle 'config/licenses.json')) -and -not (Test-Path -LiteralPath (Join-Path $bundle 'music_party'))) 'Package must exclude real user configuration, licenses and databases'
    foreach ($line in Get-Content -LiteralPath (Join-Path $bundle 'SHA256SUMS')) {
        Assert-True ($line -match '^([a-f0-9]{64})  (.+)$') 'Checksum entries must use sha256sum format'
        $expected = $Matches[1]
        $relativePath = $Matches[2]
        Assert-True ((Get-FileHash -LiteralPath (Join-Path $bundle $relativePath) -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expected) "Checksum must match $relativePath"
    }
    $deployBytes = [IO.File]::ReadAllBytes((Join-Path $bundle 'deploy-docker.sh'))
    Assert-True ($deployBytes -notcontains 13) 'Linux deployment script must use LF line endings'

    Clear-TestState
    $env:MP_UBUNTU_TEST_PLATFORM = 'linux/arm64'
    & $script -Platform linux/arm64
    Assert-True (Test-Path -LiteralPath (Join-Path $testRoot "dist/ubuntu/arm64/$bundleName.tar.gz")) 'arm64 package must keep the same filename in its own architecture directory'
    Assert-True ((Get-Location).Path -eq $startLocation) 'Build success and failure must restore the working directory'

    # Check the exact helpers used for recursive output replacement without allowing a link target to be removed.
    . (Join-Path $testRoot 'scripts/build-common.ps1')
    Assert-Fails { Get-BuildPath '../outside-project' } 'stay inside this project'
    Write-Fixture 'protected/sentinel.txt' 'must survive'
    New-Item -ItemType Directory -Path (Join-Path $testRoot 'generated-delete-test') -Force | Out-Null
    $linkType = if ($IsWindows) { 'Junction' } else { 'SymbolicLink' }
    New-Item -ItemType $linkType -Path (Join-Path $testRoot 'generated-delete-test/link') -Target (Join-Path $testRoot 'protected') | Out-Null
    Assert-Fails { Reset-BuildDirectory 'generated-delete-test' } 'junction or symlink'
    Assert-True ((Get-Content -LiteralPath (Join-Path $testRoot 'protected/sentinel.txt') -Raw) -eq 'must survive') 'Recursive cleanup must preserve junction and symlink targets'
    Write-Host 'PASS: Ubuntu packaging tool-contract tests (preflight, platform/version/source rejection, failures, HEAD, user-data exclusion, checksums, amd64/arm64).'
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    }
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $allowedPrefix = [IO.Path]::GetFullPath((Join-Path $repository 'build')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTestRoot.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to remove a test directory outside the project build directory' }
    if (Test-Path -LiteralPath $resolvedTestRoot) { Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force }
}
