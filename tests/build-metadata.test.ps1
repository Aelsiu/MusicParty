#requires -Version 5.1
# The version and naming contract is shared by all four builders.
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
. (Join-Path $repository 'scripts/build-metadata.ps1')
$testRoot = Join-Path $repository ('build/metadata-test-' + [guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in @('MP_METADATA_COMMIT', 'MP_METADATA_SHORT', 'MP_METADATA_FAIL')) {
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
function Write-Version([string]$Version) {
    [IO.File]::WriteAllText((Join-Path $testRoot 'VERSION'), "$Version`n", [Text.UTF8Encoding]::new($false))
}
try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $git = Join-Path $testRoot 'git.ps1'
    [IO.File]::WriteAllText($git, @'
if ($env:MP_METADATA_FAIL) { exit 23 }
if ($args[0] -ne '-C' -or $args[2] -ne 'rev-parse') { throw 'Expected explicit project-directory Git metadata lookup' }
if ($args -contains '--short=7') { $env:MP_METADATA_SHORT } else { $env:MP_METADATA_COMMIT }
exit 0
'@, [Text.UTF8Encoding]::new($false))
    $env:MP_METADATA_COMMIT = 'abcdef0123456789012345678901234567890123'
    $env:MP_METADATA_SHORT = 'abcdef0'
    Remove-Item Env:MP_METADATA_FAIL -ErrorAction SilentlyContinue
    Assert-Fails { Get-MusicPartyBuildMetadata $testRoot $git } 'VERSION file is missing'
    Write-Version '1.3.9'
    $metadata = Get-MusicPartyBuildMetadata $testRoot $git
    Assert-True ($metadata.Version -eq '1.3.9' -and $metadata.Commit -eq $env:MP_METADATA_COMMIT -and $metadata.ShortCommit -eq 'abcdef0') 'Metadata must keep the release version distinct from its full and short commit'
    foreach ($environmentName in @('windows', 'ubuntu', 'android', 'update')) {
        Assert-True ((Get-MusicPartyArtifactName $environmentName $metadata) -eq "MusicParty-$environmentName-1.3.9-abcdef0") 'All platforms must share exactly the same naming format'
    }
    Assert-MusicPartyBuildMetadata $testRoot $git $metadata
    Write-Version '1.4.0-beta.1'
    Assert-True ((Get-MusicPartyBuildMetadata $testRoot $git).Version -eq '1.4.0-beta.1') 'Prerelease versions must retain their exact spelling'
    Assert-Fails { Assert-MusicPartyBuildMetadata $testRoot $git $metadata } 'VERSION changed'
    foreach ($invalid in @('', 'v1.3.9', '01.3.9', '1.3', '../1.3.9', "1.3.9`n1.4.0", '1.4.0-beta.01')) {
        Write-Version $invalid
        Assert-Fails { Get-MusicPartyBuildMetadata $testRoot $git } 'VERSION'
    }
    Write-Version '1.3.9'
    $env:MP_METADATA_COMMIT = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
    $env:MP_METADATA_SHORT = 'bbbbbbb'
    Assert-Fails { Assert-MusicPartyBuildMetadata $testRoot $git $metadata } 'HEAD.*changed'
    $env:MP_METADATA_SHORT = 'abcdef0'
    Assert-Fails { Get-MusicPartyBuildMetadata $testRoot $git } 'consistent Git HEAD'
    $env:MP_METADATA_FAIL = '1'
    Assert-Fails { Get-MusicPartyBuildMetadata $testRoot $git } 'exit 23'
    Write-Host 'PASS: shared version, four artifact names, prerelease validation and build-change guards.'
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    }
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $allowedPrefix = [IO.Path]::GetFullPath((Join-Path $repository 'build')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to clean a test directory outside the project build directory' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
