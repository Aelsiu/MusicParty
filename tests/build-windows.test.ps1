#requires -Version 5.1
<# Windows build tool-contract tests; replaces tool discovery and executable transport with local PowerShell fixtures. #>
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { Write-Host 'SKIP: Windows build tool-contract tests require Windows.'; return }
$repository = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $repository ('build/windows-script-test-' + [guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in @('GOTOOLCHAIN', 'GOFLAGS', 'JAVA_HOME', 'PATH', 'VITE_APP_VERSION', 'MP_WINDOWS_TEST_ROOT',
        'MP_WINDOWS_TEST_GO_VERSION', 'MP_WINDOWS_TEST_WAILS_VERSION', 'MP_WINDOWS_TEST_FAIL', 'MP_WINDOWS_TEST_CHANGE_VERSION', 'MP_WINDOWS_TEST_CHANGE_HEAD')) {
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
    foreach ($name in @('MP_WINDOWS_TEST_GO_VERSION', 'MP_WINDOWS_TEST_WAILS_VERSION', 'MP_WINDOWS_TEST_FAIL',
            'MP_WINDOWS_TEST_CHANGE_VERSION', 'MP_WINDOWS_TEST_CHANGE_HEAD')) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
    foreach ($file in @('tools.jsonl', 'head-changed')) { Remove-Item -LiteralPath (Join-Path $testRoot $file) -ErrorAction SilentlyContinue }
    Write-Fixture 'VERSION' "1.3.9`n"
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $source = [IO.File]::ReadAllText((Join-Path $repository 'build-windows.ps1'))
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors) { throw 'build-windows.ps1 has a syntax error' }
    $functions = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in @('Get-WindowsBuildTool', 'Invoke-WindowsBuildCommand') }, $true) | Sort-Object { $_.Extent.StartOffset } -Descending
    foreach ($function in $functions) {
        if ($function.Name -eq 'Get-WindowsBuildTool') {
            $replacement = @'
function Get-WindowsBuildTool([string]$Name, [string]$Hint) {
    return Join-Path $env:MP_WINDOWS_TEST_ROOT ('tools/' + [IO.Path]::GetFileNameWithoutExtension($Name) + '.ps1')
}
'@
        } else {
            # Keep the actual command failure logic; only redirect transport to the fixture tool.
            $mapping = @'
$toolName = [IO.Path]::GetFileNameWithoutExtension($Executable)
    if ($toolName -eq 'mvnw') { $toolName = 'mvn' }
    $Executable = Join-Path $env:MP_WINDOWS_TEST_ROOT ("tools/$toolName.ps1")
    & $Executable @Arguments
'@
            $replacement = $function.Extent.Text.Replace('& $Executable @Arguments', $mapping)
        }
        $source = $source.Substring(0, $function.Extent.StartOffset) + $replacement + $source.Substring($function.Extent.EndOffset)
    }
    Write-Fixture 'build-windows.ps1' $source
    Write-Fixture 'mvnw.cmd' '@rem fixture Maven wrapper'
    foreach ($file in @('scripts/build-metadata.ps1', 'launcher/wails.json')) {
        $destination = Join-Path $testRoot $file
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repository $file) -Destination $destination
    }
    Write-Fixture 'external-api-source/app.js' '// fixture Enhanced API'
    Write-Fixture 'external-api-source/package.json' '{"name":"fixture-api"}'
    Write-Fixture 'external-api-source/package-lock.json' '{"lockfileVersion":3}'
    Write-Fixture 'external-api-source/module/example.js' '// fixture module'
    Write-Fixture 'external-api-source/util/example.js' '// fixture utility'
    Write-Fixture 'jdk/bin/javac.exe' 'fixture JDK compiler'
    Write-Fixture 'jdk/bin/jlink.exe' 'fixture JDK linker'
    New-Item -ItemType Directory -Path (Join-Path $testRoot 'music-party-web') -Force | Out-Null
    $fixturePe = New-Object 'byte[]' 128
    [BitConverter]::GetBytes([uint16]0x5a4d).CopyTo($fixturePe, 0)
    [BitConverter]::GetBytes([int32]64).CopyTo($fixturePe, 60)
    [BitConverter]::GetBytes([uint32]0x00004550).CopyTo($fixturePe, 64)
    [BitConverter]::GetBytes([uint16]0x8664).CopyTo($fixturePe, 68)
    [IO.File]::WriteAllBytes((Join-Path $testRoot 'ffmpeg.exe'), $fixturePe)
    Write-Fixture 'tools/git.ps1' @'
if ($args -contains '--version') { 'git version 2.49.0.windows.1'; exit 0 }
if ($args -contains 'rev-parse') {
    $commit = if (Test-Path -LiteralPath (Join-Path $env:MP_WINDOWS_TEST_ROOT 'head-changed')) { 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' } else { '64f8fb6aaaedb6467359aa9e12a2b9acb1f907f5' }
    if ($args -contains '--short=7') { $commit.Substring(0, 7) } else { $commit }
    exit 0
}
if ($args -contains 'status') { exit 0 }
throw "Unexpected Git invocation: $args"
'@
    Write-Fixture 'tools/go.ps1' @'
if ($env:MP_WINDOWS_TEST_GO_VERSION) { "go version go$env:MP_WINDOWS_TEST_GO_VERSION windows/amd64" }
else { 'go version go1.27.1 windows/amd64' }
exit 0
'@
    Write-Fixture 'tools/node.ps1' @'
if ($args -contains '--version') { 'v24.21.0' } else { 'x64' }
exit 0
'@
    Write-Fixture 'tools/java.ps1' @'
"    java.version = 21.0.12.1"
"    java.home = $env:MP_WINDOWS_TEST_ROOT/jdk"
'    sun.arch.data.model = 64'
exit 0
'@
    Write-Fixture 'tools/ffmpeg.ps1' @'
'ffmpeg version 7.0.1-static Copyright (c) the FFmpeg developers'
exit 0
'@
    Write-Fixture 'tools/npm.ps1' @'
if ($args -contains '--version') { '11.19.0'; exit 0 }
Add-Content -LiteralPath (Join-Path $env:MP_WINDOWS_TEST_ROOT 'tools.jsonl') -Value (@{Tool='npm';Arguments=@($args);Directory=$PWD.Path;Version=$env:VITE_APP_VERSION;GoToolchain=$env:GOTOOLCHAIN} | ConvertTo-Json -Compress)
if ($args -contains 'build') {
    New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $PWD 'dist/index.html'), "app version $env:VITE_APP_VERSION")
}
exit 0
'@
    Write-Fixture 'tools/mvn.ps1' @'
Add-Content -LiteralPath (Join-Path $env:MP_WINDOWS_TEST_ROOT 'tools.jsonl') -Value (@{Tool='maven';Arguments=@($args);Version=$env:VITE_APP_VERSION} | ConvertTo-Json -Compress)
$revision = ($args | Where-Object { $_ -like '-Drevision=*' } | Select-Object -First 1) -replace '^-Drevision=', ''
if (-not $revision) { throw 'Maven must receive the central VERSION as -Drevision' }
[IO.File]::WriteAllText((Join-Path $PWD "target/MusicParty-$revision.jar"), "server $revision")
exit 0
'@
    Write-Fixture 'tools/jlink.ps1' @'
$outputIndex = [array]::IndexOf($args, '--output')
$output = $args[$outputIndex + 1]
New-Item -ItemType Directory -Path (Join-Path $output 'bin') -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $output 'bin/java.exe'), 'fixture JRE runtime')
exit 0
'@
    Write-Fixture 'tools/wails.ps1' @'
if ($args -contains 'version') {
    if ($env:MP_WINDOWS_TEST_WAILS_VERSION) { "v$env:MP_WINDOWS_TEST_WAILS_VERSION" } else { 'v2.12.0' }
    exit 0
}
Add-Content -LiteralPath (Join-Path $env:MP_WINDOWS_TEST_ROOT 'tools.jsonl') -Value (@{Tool='wails';Arguments=@($args);Version=$env:VITE_APP_VERSION;GoToolchain=$env:GOTOOLCHAIN} | ConvertTo-Json -Compress)
if ($env:MP_WINDOWS_TEST_FAIL) { exit 29 }
$outputIndex = [array]::IndexOf($args, '-o')
if ($outputIndex -lt 0) { throw 'Wails must receive -o with the unified artifact filename' }
New-Item -ItemType Directory -Path 'build/bin' -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $PWD ('build/bin/' + $args[$outputIndex + 1])), 'fixture complete launcher')
if ($env:MP_WINDOWS_TEST_CHANGE_VERSION) { [IO.File]::WriteAllText((Join-Path $env:MP_WINDOWS_TEST_ROOT 'VERSION'), "1.4.0`n") }
if ($env:MP_WINDOWS_TEST_CHANGE_HEAD) { [IO.File]::WriteAllText((Join-Path $env:MP_WINDOWS_TEST_ROOT 'head-changed'), 'changed') }
exit 0
'@
    $env:MP_WINDOWS_TEST_ROOT = $testRoot
    Remove-Item -LiteralPath 'Env:JAVA_HOME' -ErrorAction SilentlyContinue
    $env:VITE_APP_VERSION = 'keep-caller-version'
    $env:GOTOOLCHAIN = 'keep-caller-toolchain'
    $beforeEnvironment = @{}
    foreach ($name in @('GOTOOLCHAIN', 'GOFLAGS', 'JAVA_HOME', 'PATH', 'VITE_APP_VERSION')) { $beforeEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    $startLocation = (Get-Location).Path
    $script = Join-Path $testRoot 'build-windows.ps1'
    $options = @{ FfmpegPath = (Join-Path $testRoot 'ffmpeg.exe') }
    Clear-TestState

    & $script -CheckEnvironment
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist'))) 'Preflight must not create release artifacts'
    $env:MP_WINDOWS_TEST_GO_VERSION = '1.23.11'
    Assert-Fails { & $script -CheckEnvironment } 'Go 1.23.12 or above'
    $env:MP_WINDOWS_TEST_GO_VERSION = '1.23.12'
    & $script -CheckEnvironment
    $env:MP_WINDOWS_TEST_GO_VERSION = '1.24.0'
    & $script -CheckEnvironment
    $env:MP_WINDOWS_TEST_GO_VERSION = '1.28rc1'
    Assert-Fails { & $script -CheckEnvironment } 'Go 1.23.12 or above'
    Clear-TestState
    $env:MP_WINDOWS_TEST_WAILS_VERSION = '2.16.0'
    Assert-Fails { & $script -CheckEnvironment } 'Wails CLI 2.12.0'

    Clear-TestState
    & $script @options
    $artifactName = 'MusicParty-windows-1.3.9-64f8fb6.exe'
    $artifact = Join-Path $testRoot "dist/windows/$artifactName"
    Assert-True ((Get-Content -LiteralPath $artifact -Raw) -eq 'fixture complete launcher') 'Windows release must use the unified version/commit filename'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist/windows/MusicParty.exe'))) 'Unversioned Windows files must not be published'
    $checksumText = Get-Content -LiteralPath ($artifact + '.sha256') -Raw
    Assert-True ($checksumText -eq ((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() + "  $artifactName`n")) 'Published checksum must match the final Windows artifact'
    $calls = @(Get-Content -LiteralPath (Join-Path $testRoot 'tools.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    $maven = $calls | Where-Object { $_.Tool -eq 'maven' } | Select-Object -First 1
    Assert-True ($maven.Arguments -contains '-Drevision=1.3.9' -and $maven.Version -eq '1.3.9') 'Maven and Vite must use the same central VERSION'
    $wails = $calls | Where-Object { $_.Tool -eq 'wails' } | Select-Object -First 1
    Assert-True ($wails.Arguments -contains $artifactName -and $wails.GoToolchain -eq 'local') 'Wails must receive the unified filename and use the installed Go toolchain'
    $npm = @($calls | Where-Object { $_.Tool -eq 'npm' })
    Assert-True (@($npm | Where-Object { $_.Version -ne '1.3.9' }).Count -eq 0) 'Every frontend command must receive the central VERSION'
    Assert-True (@($npm | Where-Object { $_.Directory -like '*external-api-source*' }).Count -eq 0) 'External API checkout must not be modified by npm'
    $savedArtifactHash = (Get-FileHash -LiteralPath $artifact).Hash
    $env:MP_WINDOWS_TEST_FAIL = '1'
    Assert-Fails { & $script @options } 'exit code 29'
    Assert-True ((Get-FileHash -LiteralPath $artifact).Hash -eq $savedArtifactHash) 'Failed Wails build must preserve the previous published artifact'
    Clear-TestState
    $env:MP_WINDOWS_TEST_CHANGE_VERSION = '1'
    Assert-Fails { & $script @options } 'changed|modified'
    Assert-True ((Get-FileHash -LiteralPath $artifact).Hash -eq $savedArtifactHash) 'Changed VERSION must not overwrite a published artifact'
    Clear-TestState
    $env:MP_WINDOWS_TEST_CHANGE_HEAD = '1'
    Assert-Fails { & $script @options } 'changed|modified'
    Assert-True ((Get-FileHash -LiteralPath $artifact).Hash -eq $savedArtifactHash) 'Changed HEAD must not overwrite a published artifact'
    foreach ($name in $beforeEnvironment.Keys) { Assert-True ([Environment]::GetEnvironmentVariable($name, 'Process') -eq $beforeEnvironment[$name]) "Caller environment must be restored: $name" }
    Assert-True ((Get-Location).Path -eq $startLocation) 'Working directory must be restored after success and failure'
    Write-Host 'PASS: Windows build tool-contract tests (Go range, preflight, central version/commit names, failures, metadata changes, checksums and environment restoration).'
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
