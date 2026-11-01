# Version packaging is separate from feature selection and native intermediates.
function Get-NativeRendererAndroidVersion {
    param($VersionCode=1,$VersionName='0.1.0')
    if ($VersionCode -is [bool] -or $VersionCode -is [array] -or
        [string]$VersionCode -cnotmatch '^[1-9][0-9]{0,9}$') { throw 'Android version code must be a canonical positive integer.' }
    $parsed=0L
    if (-not [long]::TryParse([string]$VersionCode,[ref]$parsed) -or $parsed -gt 2147483647) { throw 'Android version code exceeds the Android integer bound.' }
    if ($VersionName -isnot [string] -or $VersionName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$') { throw 'Android version name must be a bounded display identifier.' }
    [pscustomobject]@{code=[int]$parsed;name=$VersionName}
}
function New-NativeRendererAndroidPackagingInput {
    param([string]$AppId,[string]$PackageName,[string]$FeatureLockSha256,[string]$SourceCommit,[string]$SourceTree,[string]$SourceCompositionFingerprint,$VersionCode=1,$VersionName='0.1.0')
    $version=Get-NativeRendererAndroidVersion -VersionCode $VersionCode -VersionName $VersionName
    $record=[ordered]@{schema='rusty.quest.native_renderer_android.packaging_input.v1';app_id=$AppId;package_name=$PackageName;feature_lock_sha256=$FeatureLockSha256;source_commit=$SourceCommit;source_tree=$SourceTree;source_composition_fingerprint=$SourceCompositionFingerprint;android_version_code=$version.code;android_version_name=$version.name}
    $json=$record | ConvertTo-Json -Compress
    $hasher=[Security.Cryptography.SHA256]::Create()
    try{$sha=([BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)))).Replace('-','').ToLowerInvariant()}finally{$hasher.Dispose()}
    [pscustomobject]@{record=$record;json=$json;sha256=$sha}
}
function Get-NativeRendererAndroidPackagingOutputDirectory {
    param([string]$BaseDirectory,[Parameter(Mandatory=$true)]$Packaging)
    if($Packaging.record.android_version_code -eq 1 -and $Packaging.record.android_version_name -ceq '0.1.0'){return $BaseDirectory}
    if($Packaging.sha256 -cnotmatch '^[0-9a-f]{64}$'){throw 'Packaging fingerprint is invalid.'}
    Join-Path $BaseDirectory ('p-'+$Packaging.sha256.Substring(0,16))
}
Export-ModuleMember -Function Get-NativeRendererAndroidVersion,New-NativeRendererAndroidPackagingInput,Get-NativeRendererAndroidPackagingOutputDirectory
