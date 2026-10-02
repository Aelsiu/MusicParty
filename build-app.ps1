#requires -Version 5.1
<#
.SYNOPSIS
Build the Android client APK with an installed JDK, Android SDK and Gradle.
.DESCRIPTION
Debug is signed with the Android debug key and can be installed for testing.
Release stays explicitly unsigned unless KeystorePath and KeyAlias are supplied.
Signing passwords are read from environment variables, never command arguments.
This client connects to an existing MusicParty server; it does not bundle the server.
See BUILD.md for tool installation, supported versions and signing examples.
#>
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$BuildType = 'Debug',
    [string]$GradlePath,
    [string]$GitPath = 'git',
    [string]$JavaHome,
    [string]$AndroidSdk,
    [string]$OutputDirectory = 'dist/android',
    [string]$VersionName,
    [ValidateRange(1, 2100000000)]
    [int]$VersionCode = 1,
    [string]$KeystorePath,
    [string]$KeyAlias,
    [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
    [string]$StorePasswordEnvironment = 'MUSICPARTY_KEYSTORE_PASSWORD',
    [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
    [string]$KeyPasswordEnvironment = 'MUSICPARTY_KEY_PASSWORD',
    [switch]$Offline,
    [switch]$Clean,
    [Alias('CheckOnly')]
    [switch]$CheckEnvironment
)

$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
$projectDirectory = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$androidDirectory = Join-Path $projectDirectory 'android'
$windowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$executableSuffix = if ($windowsHost) { '.exe' } else { '' }

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    # Windows PowerShell 5.1 represents ordinary native stderr as ErrorRecords.
    # Native commands report failures through their exit code instead.
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        # Do not include the argument list: signing tools can receive sensitive data.
        throw "Command failed (exit $LASTEXITCODE): $Executable"
    }
}

function Resolve-ExistingDirectory([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "${Label} directory not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-Tool([string]$Name) {
    if (Test-Path -LiteralPath $Name -PathType Leaf) {
        return (Resolve-Path -LiteralPath $Name).Path
    }
    $command = Get-Command $Name -CommandType Application, ExternalScript -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $command) { throw "Tool not found: $Name. See BUILD.md for installation." }
    return $command.Source
}

if (-not (Test-Path -LiteralPath (Join-Path $androidDirectory 'app/build.gradle.kts') -PathType Leaf)) {
    throw 'Android project not found beside build-app.ps1.'
}
$metadataHelper = Join-Path $projectDirectory 'scripts/build-metadata.ps1'
if (-not (Test-Path -LiteralPath $metadataHelper -PathType Leaf)) {
    throw 'Missing scripts/build-metadata.ps1. Restore the complete source checkout.'
}
. $metadataHelper
$gitBinary = Resolve-Tool $GitPath
$buildMetadata = Get-MusicPartyBuildMetadata -ProjectDirectory $projectDirectory -GitBinary $gitBinary
if ($PSBoundParameters.ContainsKey('VersionName') -and $VersionName -ne $buildMetadata.Version) {
    throw "VersionName must equal the shared project version $($buildMetadata.Version). Change the root VERSION file to change the project version."
}
$VersionName = $buildMetadata.Version
if (-not $PSBoundParameters.ContainsKey('VersionCode')) {
    if ($VersionName -notmatch '^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$') {
        throw 'The project version must contain numeric major.minor.patch components to calculate Android versionCode.'
    }
    if ([long]$Matches[2] -ge 100 -or [long]$Matches[3] -ge 100) {
        throw 'Versions with minor or patch >= 100 require an explicit -VersionCode to avoid code collisions.'
    }
    $calculatedVersionCode = [long]$Matches[1] * 10000 + [long]$Matches[2] * 100 + [long]$Matches[3]
    if ($calculatedVersionCode -eq 0) { $calculatedVersionCode = 1 }
    if ($calculatedVersionCode -lt 1 -or $calculatedVersionCode -gt 2100000000) {
        throw 'Calculated Android versionCode is outside 1..2100000000. Provide -VersionCode explicitly.'
    }
    $VersionCode = [int]$calculatedVersionCode
}
$artifactName = (Get-MusicPartyArtifactName -Environment 'android' -Metadata $buildMetadata) + '.apk'
$artifactVariant = if ($BuildType -eq 'Debug') { 'debug' }
    elseif ($KeystorePath) { 'release' }
    else { 'release-unsigned' }
if (($KeystorePath -or $KeyAlias) -and $BuildType -ne 'Release') {
    throw 'KeystorePath and KeyAlias are only used with -BuildType Release.'
}
if ([bool]$KeystorePath -ne [bool]$KeyAlias) {
    throw 'Provide both -KeystorePath and -KeyAlias to sign a Release APK.'
}

# Use the actual JDK reported by Java when JAVA_HOME is not configured; this
# handles Windows javapath aliases without mistaking their directory for a JDK.
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) {
    $JavaHome = Resolve-ExistingDirectory $JavaHome 'JDK'
    $javaBinary = Join-Path $JavaHome "bin/java$executableSuffix"
} else {
    $javaBinary = Resolve-Tool 'java'
}
$javaSettings = @(Invoke-Checked $javaBinary @('-XshowSettings:properties', '-version') 2>&1) -join "`n"
if ($javaSettings -notmatch '(?m)^\s*java\.specification\.version\s*=\s*(\d+)\s*$') {
    throw 'Could not determine the JDK version. Set -JavaHome to a JDK 17 or 21 directory.'
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 17) { throw "JDK 17 or newer is required; found JDK $javaMajor." }
if (-not $JavaHome) {
    if ($javaSettings -notmatch '(?m)^\s*java\.home\s*=\s*(.+?)\s*$') {
        throw 'Could not determine java.home. Set -JavaHome explicitly.'
    }
    $JavaHome = Resolve-ExistingDirectory $Matches[1] 'JDK'
}
$javaBinary = Join-Path $JavaHome "bin/java$executableSuffix"
$javacBinary = Join-Path $JavaHome "bin/javac$executableSuffix"
if (-not (Test-Path -LiteralPath $javacBinary -PathType Leaf)) {
    throw "A complete JDK is required (javac missing in $JavaHome); a JRE is insufficient."
}

# sdk.dir has precedence in Android's Gradle plugin. Keep it and ANDROID_HOME
# consistent, and fail early if an explicit -AndroidSdk would be ignored.
$localSdk = $null
$localProperties = Join-Path $androidDirectory 'local.properties'
if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
    $properties = Get-Content -LiteralPath $localProperties -Raw
    if ($properties -match '(?m)^\s*sdk\.dir\s*=\s*(.*?)\s*$') {
        $localSdk = [regex]::Replace($Matches[1], '\\([\\ :])', '$1')
    }
}
if (-not $AndroidSdk) {
    if ($localSdk) { $AndroidSdk = $localSdk }
    elseif ($env:ANDROID_HOME) { $AndroidSdk = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
    elseif ($windowsHost -and $env:LOCALAPPDATA) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    elseif ($env:HOME) { $AndroidSdk = Join-Path $env:HOME 'Android/Sdk' }
}
if (-not $AndroidSdk) {
    throw 'Android SDK not found. Set -AndroidSdk, ANDROID_HOME or android/local.properties sdk.dir.'
}
$AndroidSdk = Resolve-ExistingDirectory $AndroidSdk 'Android SDK'
if ($localSdk) {
    $localSdk = Resolve-ExistingDirectory $localSdk 'android/local.properties SDK'
    if (-not [string]::Equals($AndroidSdk.TrimEnd('\', '/'), $localSdk.TrimEnd('\', '/'),
            $(if ($windowsHost) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }))) {
        throw '-AndroidSdk conflicts with android/local.properties sdk.dir. Align these SDK paths before building.'
    }
}
foreach ($relativePath in @('platforms/android-35/android.jar', 'build-tools/34.0.0/source.properties')) {
    if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk $relativePath) -PathType Leaf)) {
        throw "Missing SDK package: $relativePath. Run sdkmanager 'platforms;android-35' 'build-tools;34.0.0', then sdkmanager --licenses."
    }
}
$sdkLicense = Join-Path $AndroidSdk 'licenses/android-sdk-license'
if (-not (Test-Path -LiteralPath $sdkLicense -PathType Leaf) -or
        (Get-Item -LiteralPath $sdkLicense).Length -eq 0) {
    throw 'Android SDK licenses are not accepted. Run sdkmanager --licenses before building.'
}

if (-not $GradlePath) { $GradlePath = 'gradle' }
$gradleBinary = Resolve-Tool $GradlePath
$outputPath = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectDirectory $OutputDirectory))
}
$outputPath = Join-Path $outputPath $artifactVariant
if ($KeystorePath) {
    if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) { throw 'Signing keystore not found.' }
    $KeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($StorePasswordEnvironment))) {
        throw "Set the $StorePasswordEnvironment environment variable before signing."
    }
}
$apksignerName = if ($windowsHost) { 'apksigner.bat' } else { 'apksigner' }
$apksignerBinary = Join-Path $AndroidSdk "build-tools/34.0.0/$apksignerName"
if (-not (Test-Path -LiteralPath $apksignerBinary -PathType Leaf)) {
    throw 'SDK Build Tools 34.0.0 apksigner is missing. Reinstall that SDK package.'
}

$savedJavaHome = $env:JAVA_HOME
$savedAndroidHome = $env:ANDROID_HOME
$savedAndroidRoot = $env:ANDROID_SDK_ROOT
$savedPath = $env:PATH
$temporarySignedApk = $null
Push-Location $androidDirectory
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $savedPath
    $gradleInfo = @(Invoke-Checked $gradleBinary @('--version') 2>&1) -join "`n"
    if ($gradleInfo -notmatch '(?m)^Gradle (\d+)\.(\d+)(?:\.(\d+))?\s*$') {
        throw 'Could not determine a stable Gradle version. Install Gradle 8.9 and set -GradlePath.'
    }
    $gradleVersion = [version]::new([int]$Matches[1], [int]$Matches[2], $(if ($Matches[3]) { [int]$Matches[3] } else { 0 }))
    if ($gradleVersion -lt [version]'8.7.0' -or $gradleVersion.Major -ne 8) {
        throw "Gradle 8.7 or newer in the 8.x series is required; found $gradleVersion. Gradle 8.9 is recommended."
    }
    # Gradle's JVM runtime support is separate from the app's Java 17 bytecode target.
    $minimumGradleForJava = switch ($javaMajor) {
        22 { [version]'8.8.0' }
        23 { [version]'8.10.0' }
        24 { [version]'8.14.0' }
        default { [version]'8.7.0' }
    }
    if ($javaMajor -gt 24 -or $gradleVersion -lt $minimumGradleForJava) {
        throw "JDK $javaMajor cannot run Gradle $gradleVersion. Use JDK 17 or 21, or consult Gradle's Java compatibility table."
    }
    if ($gradleVersion -ne [version]'8.9.0') { Write-Warning 'The existing CI uses Gradle 8.9; other 8.x versions may produce plugin deprecation warnings.' }
    Write-Host "JDK $javaMajor`: $JavaHome"
    Write-Host "Gradle $gradleVersion`: $gradleBinary"
    Write-Host "Android SDK: $AndroidSdk (API 35 / Build Tools 34.0.0)"
    Write-Host "Project version: $VersionName (versionCode $VersionCode), commit: $($buildMetadata.ShortCommit)"
    if ($CheckEnvironment) {
        Write-Host 'Android build environment check passed. No APK was built.'
        return
    }

    $gradleArguments = @('--no-daemon', '--console=plain', '--stacktrace',
        "-Dorg.gradle.java.home=$JavaHome", '-Pandroid.builder.sdkDownload=false')
    if ($Offline) { $gradleArguments += '--offline' }
    if ($Clean) { $gradleArguments += 'clean' }
    $gradleArguments += ":app:assemble$BuildType"
    $gradleArguments += "-PversionName=$VersionName", "-PversionCode=$VersionCode"
    Invoke-Checked $gradleBinary $gradleArguments

    $variant = $BuildType.ToLowerInvariant()
    $apkName = if ($BuildType -eq 'Debug') { 'app-debug.apk' } else { 'app-release-unsigned.apk' }
    $apkSource = Join-Path $androidDirectory "app/build/outputs/apk/$variant/$apkName"
    if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) { throw "Gradle did not produce the expected APK: $apkSource" }
    if ($KeystorePath) {
        # Keep the published APK intact if signing or signature verification fails.
        $temporarySignedApk = Join-Path (Split-Path -Parent $apkSource) ("signed-" + [guid]::NewGuid().ToString('N') + '.apk')
        $signingArguments = @('sign', '--ks', $KeystorePath, '--ks-key-alias', $KeyAlias,
            '--ks-pass', "env:$StorePasswordEnvironment", '--out', $temporarySignedApk)
        if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($KeyPasswordEnvironment))) {
            $signingArguments += @('--key-pass', "env:$KeyPasswordEnvironment")
        } else {
            $signingArguments += @('--key-pass', "env:$StorePasswordEnvironment")
        }
        $signingArguments += $apkSource
        Invoke-Checked $apksignerBinary $signingArguments
        Invoke-Checked $apksignerBinary @('verify', '--verbose', $temporarySignedApk)
        $apkSource = $temporarySignedApk
    } elseif ($BuildType -eq 'Debug') {
        Invoke-Checked $apksignerBinary @('verify', '--verbose', $apkSource)
    }
    Assert-MusicPartyBuildMetadata -ProjectDirectory $projectDirectory -GitBinary $gitBinary -Expected $buildMetadata
    New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
    $apkOutput = Join-Path $outputPath $artifactName
    if ([string]::Equals([IO.Path]::GetFullPath($apkSource), $apkOutput, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'OutputDirectory must differ from the Gradle APK output directory.'
    }
    Copy-Item -LiteralPath $apkSource -Destination $apkOutput -Force
    $hash = (Get-FileHash -LiteralPath $apkOutput -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($apkOutput + '.sha256'), "$hash  $artifactName`n", [Text.UTF8Encoding]::new($false))
    Write-Host "APK: $apkOutput"
    Write-Host "SHA256: $hash"
    if ($BuildType -eq 'Release' -and -not $KeystorePath) {
        Write-Warning 'This Release APK is UNSIGNED and cannot be installed. Rebuild with -KeystorePath and -KeyAlias to sign it.'
    } elseif ($BuildType -eq 'Debug') {
        Write-Host 'Debug APK is signed for testing. Release updates must use your existing production signing key.'
    } else {
        Write-Host 'Release APK signature verified. Preserve the signing key to publish compatible future updates.'
    }
} finally {
    if ($temporarySignedApk -and (Test-Path -LiteralPath $temporarySignedApk -PathType Leaf)) {
        Remove-Item -LiteralPath $temporarySignedApk -Force
    }
    if ($temporarySignedApk -and (Test-Path -LiteralPath ($temporarySignedApk + '.idsig') -PathType Leaf)) {
        Remove-Item -LiteralPath ($temporarySignedApk + '.idsig') -Force
    }
    $env:JAVA_HOME = $savedJavaHome
    $env:ANDROID_HOME = $savedAndroidHome
    $env:ANDROID_SDK_ROOT = $savedAndroidRoot
    $env:PATH = $savedPath
    Pop-Location
}
