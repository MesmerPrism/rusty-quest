Set-StrictMode -Version Latest

function Get-SpatialOperatorPackage {
    param([Parameter(Mandatory)][bool]$Enabled,[Parameter(Mandatory)][bool]$DevelopmentFeedback)
    if($Enabled -and -not$DevelopmentFeedback){throw 'SDK Operator requires explicit public DevelopmentFeedback.'}
    $base='io.github.mesmerprism.rustyquest.spatial_worldspace_hotload'
    if($Enabled){return $base+'.operator'}
    return $base
}

function Read-SpatialOperatorManifest {
    param([Parameter(Mandatory)][bool]$Enabled)
    if(-not$Enabled){return [pscustomobject]@{package='';internet=$false;experimental_optional=$false;extract_native_libs=$false}}
    $path=Join-Path $PSScriptRoot 'OperatorDebugManifest.xml'
    $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
    $reader=[Xml.XmlReader]::Create($path,$settings)
    try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose()}
    $ns=[Xml.XmlNamespaceManager]::new($xml.NameTable);$ns.AddNamespace('a','http://schemas.android.com/apk/res/android')
    $internet=$xml.SelectNodes('/manifest/uses-permission[@a:name="android.permission.INTERNET"]',$ns).Count-eq1
    $experimental=$xml.SelectNodes('/manifest/uses-feature[@a:name="com.oculus.experimental.enabled" and @a:required="false"]',$ns).Count-eq1
    $apps=@($xml.SelectNodes('/manifest/application'))
    if($xml.DocumentElement.GetAttribute('package')-cne(Get-SpatialOperatorPackage $true $true)-or$apps.Count-ne1-or-not$internet-or-not$experimental-or
       $apps[0].GetAttribute('debuggable','http://schemas.android.com/apk/res/android')-cne'true'-or
       $apps[0].GetAttribute('extractNativeLibs','http://schemas.android.com/apk/res/android')-cne'true'){throw 'SDK Operator manifest contract differs.'}
    [pscustomobject]@{package=$xml.DocumentElement.GetAttribute('package');internet=$true;experimental_optional=$true;extract_native_libs=$true;path=$path}
}

function Assert-SpatialOperatorBuildLease {
    param([Parameter(Mandatory)]$Board,[Parameter(Mandatory)][string]$BuildLeaseId,[string]$CacheLeaseId='',
        [string]$GradleUserHome='',[Parameter(Mandatory)][string]$IntermediateRoot,[Parameter(Mandatory)][string]$OutputRoot,[Parameter(Mandatory)][string]$SourceRoot)
    function Active($lease){
        $expiry=[DateTimeOffset]::MinValue
        return $lease.status-ceq'active'-and[DateTimeOffset]::TryParse([string]$lease.lease_until,[ref]$expiry)-and$expiry-gt[DateTimeOffset]::UtcNow
    }
    $build=@($Board.active_leases|Where-Object{$_.id-ceq$BuildLeaseId})
    if($build.Count-ne1-or-not(Active $build[0])){throw 'Supplied SDK build lease is absent or expired.'}
    if($build[0].resource-ceq'apk-build:rusty-quest'){return $build[0]}
    if($build[0].resource-cne'apk-build:rusty-quest-sdk-hotload'-or[string]::IsNullOrWhiteSpace($CacheLeaseId)-or[string]::IsNullOrWhiteSpace($GradleUserHome)){throw 'Isolated SDK build requires its fixed build resource and explicit cache lease.'}
    $paths=@($GradleUserHome,$IntermediateRoot,$OutputRoot,$SourceRoot)|ForEach-Object{[IO.Path]::GetFullPath($_).TrimEnd('\','/')}
    for($i=0;$i-lt$paths.Count;$i++){
        for($j=$i+1;$j-lt$paths.Count;$j++){
            if($paths[$i].Equals($paths[$j],[StringComparison]::OrdinalIgnoreCase)-or$paths[$i].StartsWith($paths[$j]+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or$paths[$j].StartsWith($paths[$i]+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'SDK cache, staging, outputs and source must be separate roots.'}
        }
        if($i-lt3){$cursor=$paths[$i];while($cursor){if(Test-Path -LiteralPath $cursor){if((Get-Item -LiteralPath $cursor).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Isolated SDK build roots cannot use filesystem aliases.'}};$parent=Split-Path $cursor -Parent;if($parent-eq$cursor){break};$cursor=$parent}}
    }
    $cache=@($Board.active_leases|Where-Object{$_.id-ceq$CacheLeaseId})
    if($cache.Count-ne1-or-not(Active $cache[0])-or[string]::IsNullOrWhiteSpace([string]$build[0].owner)-or$cache[0].owner-cne$build[0].owner-or
       -not([string]$cache[0].resource).StartsWith('build-cache:',[StringComparison]::Ordinal)-or
       -not([IO.Path]::GetFullPath(([string]$cache[0].resource).Substring(12))).Equals($paths[0],[StringComparison]::OrdinalIgnoreCase)){throw 'Isolated SDK cache lease does not cover the exact cache and build owner.'}
    $build[0]
}

Export-ModuleMember -Function Get-SpatialOperatorPackage,Read-SpatialOperatorManifest,Assert-SpatialOperatorBuildLease
