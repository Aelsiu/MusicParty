# Shared version and artifact naming; compatible with Windows PowerShell 5.1.
function Invoke-MusicPartyMetadataGit([string]$GitBinary, [string[]]$Arguments) {
    $global:LASTEXITCODE = 0
    $output = & $GitBinary @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Git metadata command failed (exit $LASTEXITCODE). Build from a Git checkout with a valid HEAD." }
    return ($output | Out-String).Trim()
}

function Get-MusicPartyBuildMetadata([string]$ProjectDirectory, [string]$GitBinary) {
    $versionPath = Join-Path $ProjectDirectory 'VERSION'
    if (-not (Test-Path -LiteralPath $versionPath -PathType Leaf)) { throw 'Project VERSION file is missing. Restore it from the source repository.' }
    $version = [IO.File]::ReadAllText($versionPath).Trim()
    if ($version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$') {
        throw 'Project VERSION must be a version such as 1.3.9 or 1.4.0-beta.1, without a v prefix or path characters.'
    }
    if ($Matches[4]) {
        foreach ($identifier in $Matches[4].Split('.')) {
            if ($identifier -match '^0[0-9]+$') { throw 'Numeric prerelease identifiers in VERSION must not have leading zeros.' }
        }
    }
    $commit = Invoke-MusicPartyMetadataGit $GitBinary @('-C', $ProjectDirectory, 'rev-parse', 'HEAD')
    $shortCommit = Invoke-MusicPartyMetadataGit $GitBinary @('-C', $ProjectDirectory, 'rev-parse', '--short=7', 'HEAD')
    if ($commit -notmatch '^[0-9a-f]{40}$' -or $shortCommit -notmatch '^[0-9a-f]{7,40}$' -or -not $commit.StartsWith($shortCommit, [StringComparison]::Ordinal)) {
        throw 'Could not determine a consistent Git HEAD commit for artifact naming.'
    }
    return @{ Version = $version; Commit = $commit; ShortCommit = $shortCommit }
}

function Get-MusicPartyArtifactName {
    param(
        [ValidateSet('windows', 'ubuntu', 'android', 'update')][string]$Environment,
        [hashtable]$Metadata
    )
    return "MusicParty-$Environment-$($Metadata.Version)-$($Metadata.ShortCommit)"
}

function Assert-MusicPartyBuildMetadata([string]$ProjectDirectory, [string]$GitBinary, [hashtable]$Expected) {
    $current = Get-MusicPartyBuildMetadata $ProjectDirectory $GitBinary
    if ($current.Commit -ne $Expected.Commit -or $current.Version -ne $Expected.Version) {
        throw 'HEAD or project VERSION changed during the build. Run the build again before publishing an artifact.'
    }
}
