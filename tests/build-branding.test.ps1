#requires -Version 5.1
<# Runs the Windows build against disposable tool fixtures to verify the launcher branding test gate. #>
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { Write-Host 'SKIP: Windows branding build gate requires Windows.'; return }
$repository = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $repository ('build/branding-script-test-' + [guid]::NewGuid().ToString('N'))
$startLocation = (Get-Location).Path
$savedEnvironment = @{}
foreach ($name in @('GOTOOLCHAIN', 'GOFLAGS', 'JAVA_HOME', 'PATH', 'VITE_APP_VERSION', 'MP_BRANDING_TEST_ROOT', 'MP_BRANDING_TEST_FAIL')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}
function Write-Fixture([string]$RelativePath, [string]$Content) {
    $path = Join-Path $testRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $source = [IO.File]::ReadAllText((Join-Path $repository 'build-windows.ps1'))
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors) { throw 'build-windows.ps1 has a syntax error' }
    $functions = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in @('Get-WindowsBuildTool', 'Invoke-WindowsBuildCommand') }, $true) | Sort-Object { $_.Extent.StartOffset } -Descending
    Assert-True (@($functions).Count -eq 2) 'The fixture must replace only tool discovery and executable transport'
    foreach ($function in $functions) {
        if ($function.Name -eq 'Get-WindowsBuildTool') {
            $replacement = @'
function Get-WindowsBuildTool([string]$Name, [string]$Hint) {
    return Join-Path $env:MP_BRANDING_TEST_ROOT ('tools/' + [IO.Path]::GetFileNameWithoutExtension($Name) + '.ps1')
}
'@
        } else {
            # Retain the real exit-code check, build order and finally blocks.
            $mapping = @'
$toolName = [IO.Path]::GetFileNameWithoutExtension($Executable)
    if ($toolName -eq 'mvnw') { $toolName = 'mvn' }
    $Executable = Join-Path $env:MP_BRANDING_TEST_ROOT ("tools/$toolName.ps1")
    & $Executable @Arguments
'@
            Assert-True ($function.Extent.Text.Contains('& $Executable @Arguments')) 'Executable transport must still use the expected invocation'
            $replacement = $function.Extent.Text.Replace('& $Executable @Arguments', $mapping)
        }
        $source = $source.Substring(0, $function.Extent.StartOffset) + $replacement + $source.Substring($function.Extent.EndOffset)
    }
    Write-Fixture 'build-windows.ps1' $source
    Write-Fixture 'VERSION' "1.3.9`n"
    Write-Fixture 'mvnw.cmd' '@rem fixture Maven wrapper'
    foreach ($file in @('scripts/build-metadata.ps1', 'launcher/wails.json')) {
        $destination = Join-Path $testRoot $file
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repository $file) -Destination $destination
    }
    foreach ($file in @('external-api-source/app.js', 'external-api-source/package.json', 'external-api-source/package-lock.json', 'external-api-source/module/example.js', 'external-api-source/util/example.js', 'jdk/bin/javac.exe', 'jdk/bin/jlink.exe')) {
        Write-Fixture $file 'fixture asset'
    }
    Write-Fixture 'launcher/bin/previous-assets.txt' 'keep existing assets if configuration tests fail'
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
    if ($args -contains '--short=7') { '64f8fb6' } else { '64f8fb6aaaedb6467359aa9e12a2b9acb1f907f5' }
    exit 0
}
throw "Unexpected Git invocation: $args"
'@
    Write-Fixture 'tools/go.ps1' @'
if ($args -contains 'version') { 'go version go1.23.12 windows/amd64'; exit 0 }
Add-Content -LiteralPath (Join-Path $env:MP_BRANDING_TEST_ROOT 'tools.jsonl') -Value (@{Tool='go';Arguments=@($args);Directory=$PWD.Path} | ConvertTo-Json -Compress)
if ($env:MP_BRANDING_TEST_FAIL) { exit 37 }
exit 0
'@
    Write-Fixture 'tools/node.ps1' @'
if ($args -contains '--version') { 'v24.21.0' } else { 'x64' }
exit 0
'@
    Write-Fixture 'tools/java.ps1' @'
'    java.version = 21.0.12.1'
"    java.home = $env:MP_BRANDING_TEST_ROOT/jdk"
'    sun.arch.data.model = 64'
exit 0
'@
    Write-Fixture 'tools/ffmpeg.ps1' @'
'ffmpeg version 7.0.1-static fixture'
exit 0
'@
    Write-Fixture 'tools/npm.ps1' @'
if ($args -contains '--version') { '11.19.0'; exit 0 }
Add-Content -LiteralPath (Join-Path $env:MP_BRANDING_TEST_ROOT 'tools.jsonl') -Value (@{Tool='npm';Arguments=@($args)} | ConvertTo-Json -Compress)
if ($args -contains 'build') {
    New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $PWD 'dist/index.html'), 'fixture web app')
}
exit 0
'@
    Write-Fixture 'tools/mvn.ps1' @'
Add-Content -LiteralPath (Join-Path $env:MP_BRANDING_TEST_ROOT 'tools.jsonl') -Value (@{Tool='maven';Arguments=@($args)} | ConvertTo-Json -Compress)
$revision = ($args | Where-Object { $_ -like '-Drevision=*' } | Select-Object -First 1) -replace '^-Drevision=', ''
[IO.File]::WriteAllText((Join-Path $PWD "target/MusicParty-$revision.jar"), 'fixture server')
exit 0
'@
    Write-Fixture 'tools/jlink.ps1' @'
$output = $args[([array]::IndexOf($args, '--output') + 1)]
New-Item -ItemType Directory -Path (Join-Path $output 'bin') -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $output 'bin/java.exe'), 'fixture runtime')
exit 0
'@
    Write-Fixture 'tools/wails.ps1' @'
if ($args -contains 'version') { 'v2.12.0'; exit 0 }
Add-Content -LiteralPath (Join-Path $env:MP_BRANDING_TEST_ROOT 'tools.jsonl') -Value (@{Tool='wails';Arguments=@($args)} | ConvertTo-Json -Compress)
$output = $args[([array]::IndexOf($args, '-o') + 1)]
New-Item -ItemType Directory -Path 'build/bin' -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $PWD ('build/bin/' + $output)), 'fixture complete launcher')
exit 0
'@
    $env:MP_BRANDING_TEST_ROOT = $testRoot
    Remove-Item -LiteralPath 'Env:JAVA_HOME' -ErrorAction SilentlyContinue
    $env:GOTOOLCHAIN = 'keep-caller-toolchain'
    $env:VITE_APP_VERSION = 'keep-caller-version'
    $script = Join-Path $testRoot 'build-windows.ps1'
    $options = @{ FfmpegPath = (Join-Path $testRoot 'ffmpeg.exe') }
    $env:MP_BRANDING_TEST_FAIL = '1'
    $failure = $null
    try { & $script @options } catch { $failure = $_ }
    Assert-True ($null -ne $failure -and $failure.Exception.Message -match 'exit code 37') 'Failed Go configuration tests must terminate the Windows build'
    $calls = @(Get-Content -LiteralPath (Join-Path $testRoot 'tools.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    Assert-True ($calls.Count -eq 1 -and $calls[0].Tool -eq 'go') 'Go configuration failure must stop before frontend, Maven or Wails packaging'
    Assert-True (($calls[0].Arguments -join ' ') -eq 'test -mod=readonly ./pkg/config') 'The gate must run the actual launcher config package without rewriting go.mod'
    Assert-True ($calls[0].Directory -eq (Join-Path $testRoot 'launcher')) 'Configuration tests must run from the launcher module'
    Assert-True (Test-Path -LiteralPath (Join-Path $testRoot 'launcher/bin/previous-assets.txt')) 'Failed configuration tests must preserve existing launcher assets'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist'))) 'Failed configuration tests must not publish release artifacts'
    Assert-True ((Get-Location).Path -eq $startLocation) 'Failure must restore the caller working directory'
    Assert-True ($env:GOTOOLCHAIN -eq 'keep-caller-toolchain' -and $env:VITE_APP_VERSION -eq 'keep-caller-version') 'Failure must restore the caller build environment'

    Remove-Item -LiteralPath 'Env:MP_BRANDING_TEST_FAIL'
    Remove-Item -LiteralPath (Join-Path $testRoot 'tools.jsonl')
    & $script @options
    $calls = @(Get-Content -LiteralPath (Join-Path $testRoot 'tools.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    Assert-True ($calls[0].Tool -eq 'go' -and $calls[1].Tool -eq 'npm') 'Passing config tests must continue to the web build in order'
    Assert-True (@($calls | Where-Object { $_.Tool -eq 'maven' }).Count -eq 1 -and @($calls | Where-Object { $_.Tool -eq 'wails' }).Count -eq 1) 'Passing config tests must allow both server and launcher packaging'
    Assert-True (Test-Path -LiteralPath (Join-Path $testRoot 'dist/windows/MusicParty-windows-1.3.9-64f8fb6.exe')) 'Passing config tests must allow release artifact publication'
    Assert-True ((Get-Location).Path -eq $startLocation) 'Success must restore the caller working directory'
    Assert-True ($env:GOTOOLCHAIN -eq 'keep-caller-toolchain' -and $env:VITE_APP_VERSION -eq 'keep-caller-version') 'Success must restore the caller build environment'
    Write-Host 'PASS: Windows launcher config gate (failure stops packaging; success continues; working directory and environment restored).'
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
