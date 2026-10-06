Set-StrictMode -Version Latest

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
Export-ModuleMember -Function New-SpatialSourceVersionMetadata,Assert-SpatialSourceVersionBadging
