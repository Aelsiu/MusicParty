#requires -Version 5.1
<# Tool-contract tests: requires a local JDK 17 or 21, but no SDK or Gradle. #>
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $repository ('build/android-script-test-' + [guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in @('MP_TEST_GRADLE_FAIL', 'MP_TEST_GRADLE_VERSION', 'MP_TEST_SIGN_FAIL', 'MP_TEST_TOOL_LOG',
        'MP_TEST_CHANGE_COMMIT', 'MP_TEST_CHANGE_VERSION', 'MP_TEST_CURRENT_COMMIT',
        'MUSICPARTY_KEYSTORE_PASSWORD', 'MUSICPARTY_KEY_PASSWORD')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
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

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repository 'build-app.ps1') -Destination $testRoot
    Write-Fixture 'scripts/placeholder' ''
    Copy-Item -LiteralPath (Join-Path $repository 'scripts/build-metadata.ps1') -Destination (Join-Path $testRoot 'scripts/build-metadata.ps1')
    Write-Fixture 'VERSION' '1.3.9'
    Write-Fixture 'android/app/build.gradle.kts' '// fixture project'
    Write-Fixture 'sdk/platforms/android-35/android.jar' 'fixture'
    Write-Fixture 'sdk/build-tools/34.0.0/source.properties' 'Pkg.Revision=34.0.0'
    Write-Fixture 'sdk/licenses/android-sdk-license' 'fixture license'
    Write-Fixture 'gradle.ps1' @'
if ($args -contains '--version') {
    if ($env:MP_TEST_GRADLE_VERSION) { Write-Output "Gradle $env:MP_TEST_GRADLE_VERSION" }
    else { Write-Output 'Gradle 8.9' }
    exit 0
}
Add-Content -LiteralPath $env:MP_TEST_TOOL_LOG -Value ('gradle ' + ($args -join ' '))
if ($env:MP_TEST_GRADLE_FAIL) { exit 23 }
$variant = if ($args -contains ':app:assembleRelease') { 'release' } else { 'debug' }
$name = if ($variant -eq 'release') { 'app-release-unsigned.apk' } else { 'app-debug.apk' }
$output = Join-Path $PWD "app/build/outputs/apk/$variant/$name"
New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
[IO.File]::WriteAllText($output, "fixture $variant APK")
if ($env:MP_TEST_CHANGE_COMMIT) { $env:MP_TEST_CURRENT_COMMIT = 'abcdef1' + ('b' * 33) }
if ($env:MP_TEST_CHANGE_VERSION) { [IO.File]::WriteAllText((Join-Path (Split-Path -Parent $PWD.Path) 'VERSION'), '9.9.9') }
exit 0
'@
    Write-Fixture 'git.ps1' @'
if ($args -contains 'rev-parse') {
    if ($args -contains '--is-inside-work-tree') { Write-Output 'true'; exit 0 }
    if ($args -contains '--show-toplevel') { Write-Output $args[1]; exit 0 }
    $commit = if ($env:MP_TEST_CURRENT_COMMIT) { $env:MP_TEST_CURRENT_COMMIT } else { '64f8fb6' + ('a' * 33) }
    if (@($args | Where-Object { $_ -like '--short*' }).Count -gt 0) { Write-Output $commit.Substring(0, 7) }
    else { Write-Output $commit }
    exit 0
}
throw ('Unexpected metadata Git operation: ' + ($args -join ' '))
'@
    Write-Fixture 'sdk/build-tools/34.0.0/mock-apksigner.ps1' @'
Add-Content -LiteralPath $env:MP_TEST_TOOL_LOG -Value ('apksigner ' + ($args -join ' '))
if ($args[0] -eq 'sign') {
    if ($env:MP_TEST_SIGN_FAIL) { exit 31 }
    $outIndex = [array]::IndexOf($args, '--out')
    [IO.File]::WriteAllText($args[$outIndex + 1], 'fixture signed APK')
}
exit 0
'@
    $windowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
    $shellExecutable = if ($PSVersionTable.PSEdition -eq 'Desktop') {
        Join-Path $PSHOME 'powershell.exe'
    } elseif ($windowsHost) { Join-Path $PSHOME 'pwsh.exe' } else { Join-Path $PSHOME 'pwsh' }
    if ($windowsHost) {
        Write-Fixture 'sdk/build-tools/34.0.0/apksigner.bat' "@echo off`r`n`"$shellExecutable`" -NoProfile -ExecutionPolicy Bypass -File `"%~dp0mock-apksigner.ps1`" %*`r`nexit /b %errorlevel%`r`n"
    } else {
        Write-Fixture 'sdk/build-tools/34.0.0/apksigner' "#!/bin/sh`nexec '$shellExecutable' -NoProfile -File `"`$0.ps1`" `"`$@`"`n"
        Copy-Item -LiteralPath (Join-Path $testRoot 'sdk/build-tools/34.0.0/mock-apksigner.ps1') -Destination (Join-Path $testRoot 'sdk/build-tools/34.0.0/apksigner.ps1')
        & chmod +x (Join-Path $testRoot 'sdk/build-tools/34.0.0/apksigner')
        if ($LASTEXITCODE -ne 0) { throw 'Could not make mock apksigner executable.' }
    }
    $env:MP_TEST_TOOL_LOG = Join-Path $testRoot 'tools.log'
    $env:MP_TEST_GRADLE_FAIL = $null
    $env:MP_TEST_GRADLE_VERSION = $null
    $env:MP_TEST_SIGN_FAIL = $null
    $env:MP_TEST_CHANGE_COMMIT = $null
    $env:MP_TEST_CHANGE_VERSION = $null
    $env:MP_TEST_CURRENT_COMMIT = $null
    $script = Join-Path $testRoot 'build-app.ps1'
    $options = @{ GradlePath = (Join-Path $testRoot 'gradle.ps1'); AndroidSdk = (Join-Path $testRoot 'sdk'); GitPath = (Join-Path $testRoot 'git.ps1') }
    $artifactName = 'MusicParty-android-1.3.9-64f8fb6.apk'
    $beforeJavaHome = $env:JAVA_HOME
    $beforeAndroidHome = $env:ANDROID_HOME
    $beforePath = $env:PATH
    & $script @options -CheckEnvironment
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'dist/android'))) 'Preflight must not create an APK output directory'
    Assert-True ($env:JAVA_HOME -eq $beforeJavaHome -and $env:ANDROID_HOME -eq $beforeAndroidHome -and $env:PATH -eq $beforePath) 'Environment must be restored after preflight'

    Assert-Fails { & $script @options -VersionName '2.3.4-test' -CheckEnvironment } 'VersionName must equal the shared project version 1.3.9'
    & $script @options -Offline -VersionName '1.3.9' -VersionCode 20304
    $debugApk = Join-Path $testRoot "dist/android/debug/$artifactName"
    Assert-True (Test-Path -LiteralPath $debugApk) 'Debug APK must be copied'
    $toolLog = Get-Content -LiteralPath $env:MP_TEST_TOOL_LOG -Raw
    Assert-True ($toolLog -match '--offline' -and $toolLog -match '-PversionName=1.3.9' -and $toolLog -match '-PversionCode=20304') 'Offline and shared version arguments must reach Gradle'
    Assert-True ($toolLog -match 'apksigner verify') 'Debug APK signature must be verified'
    $hashText = Get-Content -LiteralPath ($debugApk + '.sha256') -Raw
    Assert-True ($hashText.StartsWith((Get-FileHash -LiteralPath $debugApk).Hash.ToLowerInvariant())) 'Checksum must match the copied APK'

    & $script @options -BuildType Release
    Assert-True (Test-Path -LiteralPath (Join-Path $testRoot "dist/android/release-unsigned/$artifactName")) 'Unsigned release must be kept in an explicit unsigned subdirectory'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot "dist/android/release/$artifactName"))) 'Unsigned release must not be advertised as signed'
    $toolLog = Get-Content -LiteralPath $env:MP_TEST_TOOL_LOG -Raw
    Assert-True ($toolLog -match '-PversionCode=10309') 'Default Android versionCode must derive from shared major.minor.patch'

    $env:MP_TEST_CHANGE_COMMIT = '1'
    Assert-Fails { & $script @options -OutputDirectory 'changed-commit-output' } 'changed|commit|metadata'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'changed-commit-output'))) 'Changed HEAD must not publish an incorrectly named APK'
    $env:MP_TEST_CHANGE_COMMIT = $null
    $env:MP_TEST_CURRENT_COMMIT = $null
    $env:MP_TEST_CHANGE_VERSION = '1'
    Assert-Fails { & $script @options -OutputDirectory 'changed-version-output' } 'changed|version|metadata'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'changed-version-output'))) 'Changed VERSION must not publish an incorrectly named APK'
    $env:MP_TEST_CHANGE_VERSION = $null
    Write-Fixture 'VERSION' '1.3.9'

    Write-Fixture 'VERSION' '1.100.0'
    Assert-Fails { & $script @options -CheckEnvironment } 'explicit -VersionCode'
    & $script @options -VersionCode 20000 -CheckEnvironment
    Write-Fixture 'VERSION' '0.0.0'
    & $script @options -OutputDirectory 'zero-version-output'
    Assert-True (Test-Path -LiteralPath (Join-Path $testRoot 'zero-version-output/debug/MusicParty-android-0.0.0-64f8fb6.apk')) 'Zero version must keep the shared artifact version'
    $zeroVersionBuild = @(Get-Content -LiteralPath $env:MP_TEST_TOOL_LOG | Where-Object { $_ -match 'gradle .*?-PversionName=0.0.0' })
    Assert-True ($zeroVersionBuild.Count -eq 1 -and $zeroVersionBuild[0] -match '-PversionCode=1(?:\s|$)') 'Version 0.0.0 must fall back to Android versionCode 1'
    Write-Fixture 'VERSION' '1.3.9'

    $env:MP_TEST_GRADLE_FAIL = '1'
    Assert-Fails { & $script @options -OutputDirectory 'failed-output' } 'exit 23'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testRoot 'failed-output'))) 'Gradle failure must not publish stale artifacts'
    Assert-True ($env:JAVA_HOME -eq $beforeJavaHome -and $env:ANDROID_HOME -eq $beforeAndroidHome -and $env:PATH -eq $beforePath) 'Environment must be restored after Gradle failure'
    $env:MP_TEST_GRADLE_FAIL = $null
    $env:MP_TEST_GRADLE_VERSION = '8.6'
    Assert-Fails { & $script @options -CheckEnvironment } 'Gradle 8.7'
    $env:MP_TEST_GRADLE_VERSION = '9.0'
    Assert-Fails { & $script @options -CheckEnvironment } '8.x'
    $env:MP_TEST_GRADLE_VERSION = $null

    Write-Fixture 'release.jks' 'fixture keystore'
    $keystore = Join-Path $testRoot 'release.jks'
    $env:MUSICPARTY_KEYSTORE_PASSWORD = $null
    Assert-Fails { & $script @options -BuildType Release -KeystorePath $keystore -KeyAlias musicparty } 'MUSICPARTY_KEYSTORE_PASSWORD'
    $env:MUSICPARTY_KEYSTORE_PASSWORD = 'fixture-secret-never-log'
    $env:MUSICPARTY_KEY_PASSWORD = $null
    & $script @options -BuildType Release -KeystorePath $keystore -KeyAlias musicparty
    $signedApk = Join-Path $testRoot "dist/android/release/$artifactName"
    Assert-True ((Get-Content -LiteralPath $signedApk -Raw) -eq 'fixture signed APK') 'Signed output must come from apksigner'
    $toolLog = Get-Content -LiteralPath $env:MP_TEST_TOOL_LOG -Raw
    Assert-True ($toolLog -match 'env:MUSICPARTY_KEYSTORE_PASSWORD' -and $toolLog -notmatch 'fixture-secret-never-log') 'Signing must pass environment references instead of secret values'
    $savedHash = (Get-FileHash -LiteralPath $signedApk).Hash
    $env:MP_TEST_SIGN_FAIL = '1'
    Assert-Fails { & $script @options -BuildType Release -KeystorePath $keystore -KeyAlias musicparty } 'exit 31'
    Assert-True ((Get-FileHash -LiteralPath $signedApk).Hash -eq $savedHash) 'Failed signing must preserve the previous artifact'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $testRoot 'android/app/build/outputs/apk/release') -Filter 'signed-*').Count -eq 0) 'Temporary signed artifacts must be cleaned up'

    Write-Host 'PASS: Android build script tool-contract tests (shared version/commit naming, metadata guards, preflight, Debug, unsigned/signed Release, tool failures and secret handling).'
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name]) }
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $allowedPrefix = [IO.Path]::GetFullPath((Join-Path $repository 'build')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTestRoot.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove a test directory outside the project build directory.'
    }
    if (Test-Path -LiteralPath $resolvedTestRoot) { Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force }
}
