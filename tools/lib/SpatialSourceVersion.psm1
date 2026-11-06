Set-StrictMode -Version Latest

function Resolve-SpatialSourceRuntimeStampSelection {
    param([bool]$Requested,[bool]$SourceVersionEnabled,[string]$BuildMode,[string]$BuildType,[bool]$PublicationBuild)
    if(-not$Requested){return $false}
    if(-not$SourceVersionEnabled-or$BuildMode-ne'DevFast'-or$BuildType-ne'Debug'-or$PublicationBuild){throw 'UseSourceRuntimeStamp requires source-composition version naming, DevFast debug and non-publication.'}
    return $true
}

function New-SpatialSourceVersionMetadata {
    param([Parameter(Mandatory)][string]$SourceCompositionFingerprint,[switch]$Enabled)
    if($SourceCompositionFingerprint-cnotmatch'^[a-f0-9]{64}\z'){throw 'Exact resolved source composition fingerprint required'}
    return [ordered]@{
        enabled=[bool]$Enabled
        source_composition_fingerprint=$SourceCompositionFingerprint
        version_code=1
        version_name=$(if($Enabled){'0.1.0+src.'+$SourceCompositionFingerprint}else{'0.1.0'})
        identity_scope='Declared source composition metadata only; not installed APK bytes, signer or provenance'
    }
}

function Assert-SpatialSourceVersionBadging {
    param([Parameter(Mandatory)][string]$BadgingText,[Parameter(Mandatory)][string]$PackageName,[Parameter(Mandatory)][Collections.IDictionary]$Metadata)
    if($Metadata.enabled-isnot[bool]){throw 'Explicit source version selection required'}
    $expected=New-SpatialSourceVersionMetadata -SourceCompositionFingerprint $Metadata.source_composition_fingerprint -Enabled:$Metadata.enabled
    if($Metadata.version_code-ne$expected.version_code-or$Metadata.version_name-cne$expected.version_name){throw 'Source version metadata differs from resolved composition'}
    $rows=@($BadgingText-split'\r?\n'|Where-Object {$_-cmatch'^package:'})
    if($rows.Count-ne1){throw 'APK package metadata absent or ambiguous'}
    foreach($key in @('name','versionCode','versionName')){
        if([regex]::Matches($rows[0],'(?:^|\s)'+$key+'=').Count-ne1){throw 'APK package identity key absent or ambiguous'}
    }
    $match=[regex]::Match($rows[0],"^package:\s+name='([^']+)'\s+versionCode='([0-9]+)'\s+versionName='([^']*)'(?:\s|$)")
    if(-not$match.Success-or$match.Groups[1].Value-cne$PackageName-or$match.Groups[2].Value-cne'1'-or$match.Groups[3].Value-cne$expected.version_name){throw 'APK source version readback differs from resolved composition'}
    return $expected
}
function Assert-SpatialSourceVersionResource {
    param([Parameter(Mandatory)][string]$ApkPath,[Parameter(Mandatory)][Collections.IDictionary]$Metadata,[bool]$RuntimeStampEnabled)
    $expected=New-SpatialSourceVersionMetadata -SourceCompositionFingerprint $Metadata.source_composition_fingerprint -Enabled:$Metadata.enabled
    if($RuntimeStampEnabled -and -not $expected.enabled){throw 'Runtime source stamp requires enabled source version'}
    $name='META-INF/rusty-quest/source-version.v1.txt'
    $stream=[IO.File]::Open($ApkPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        $archive=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Read,$true)
        try {
            $entries=@($archive.Entries|Where-Object {$_.FullName-ceq$name})
            if(-not$RuntimeStampEnabled){if($entries.Count){throw 'Unselected runtime source stamp packaged'};return $null}
            if($entries.Count-ne1-or$entries[0].Length-ne75){throw 'Exact unique bounded runtime source stamp required'}
            $input=$entries[0].Open()
            try {
                $bytes=[byte[]]::new(76);$length=0
                while($length-lt$bytes.Length){$count=$input.Read($bytes,$length,$bytes.Length-$length);if($count-eq0){break};$length+=$count}
                $actual=[Text.Encoding]::ASCII.GetString($bytes,0,$length)
                if($length-ne75-or$actual-cne($expected.version_name+"`n")){throw 'Packaged runtime source version differs from resolved composition'}
                $sha=[Security.Cryptography.SHA256]::HashData([byte[]]$bytes[0..74])
                return [ordered]@{mode='java-resource-nonconstant-v1';entry=$name;bytes=$length;sha256=([Convert]::ToHexString($sha)).ToLowerInvariant();version_name=$expected.version_name;unique_entry_verified=$true;runtime_authority=$false}
            } finally {$input.Dispose()}
        } finally {$archive.Dispose()}
    } finally {$stream.Dispose()}
}
Export-ModuleMember -Function New-SpatialSourceVersionMetadata,Assert-SpatialSourceVersionBadging,Assert-SpatialSourceVersionResource,Resolve-SpatialSourceRuntimeStampSelection
