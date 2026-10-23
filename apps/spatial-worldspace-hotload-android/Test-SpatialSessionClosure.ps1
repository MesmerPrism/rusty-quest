[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SessionCompositionManifest,
    [Parameter(Mandatory)][string]$PrivateAppIdentityManifest,
    [Parameter(Mandatory)][string]$AssetsDirectory,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion -lt [version]'7.6'){throw 'Requires pwsh 7.6 or newer.'}
if(-not [IO.Path]::IsPathRooted($OutputDirectory) -or (Test-Path -LiteralPath $OutputDirectory)){throw 'Fresh absolute test output required.'}
Import-Module (Join-Path $PSScriptRoot 'SpatialSessionComposition.psm1') -Force
$session=Read-SpatialSessionComposition -Manifest $SessionCompositionManifest -IdentityManifest $PrivateAppIdentityManifest
$null=New-Item -ItemType Directory -Path $OutputDirectory
function Pin([string]$Path){$f=Get-Item -LiteralPath $Path;[ordered]@{path=$f.FullName;bytes=$f.Length;sha256=(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}}
# Exercise the actual build reader, including a correctly rehashed but substituted lock.
$admissionCases=[Collections.Generic.List[string]]::new()
if(@($session.assets|Where-Object path -CEQ 'session-assets/feature-lock.json').Count -ne 1){throw 'Prepared lock is not a unique staged build asset.'}
$admissionCases.Add('positive-exact-lock-staged')
foreach($case in @('missing-lock','wrong-anchor','wrong-source-binding')){
    $document=Get-Content -LiteralPath $SessionCompositionManifest -Raw|ConvertFrom-Json
    if($case -ceq 'missing-lock'){
        $document.PSObject.Properties.Remove('packaged_feature_lock')
        $reason='Explicit prepared SDK packaged feature lock is required.'
    }else{
        $lockDocument=Get-Content -LiteralPath $session.feature_lock.path -Raw|ConvertFrom-Json
        if($case -ceq 'wrong-anchor'){$lockDocument.build_inputs.private_asset_closure.provider_manifest_sha256='0'*64}
        else{$lockDocument.source_binding.java_source_files_sha256='0'*64}
        $lockPath=Join-Path $OutputDirectory ($case+'-feature-lock.json')
        [IO.File]::WriteAllText($lockPath,($lockDocument|ConvertTo-Json -Depth 40)+"`n",[Text.UTF8Encoding]::new($false))
        $document.packaged_feature_lock=Pin $lockPath
        $reason='SDK packaged feature lock differs from the exact selected closure/source binding.'
    }
    $manifestPath=Join-Path $OutputDirectory ($case+'-composition.json')
    [IO.File]::WriteAllText($manifestPath,($document|ConvertTo-Json -Depth 40)+"`n",[Text.UTF8Encoding]::new($false))
    $rejection=$null
    try{$null=Read-SpatialSessionComposition -Manifest $manifestPath -IdentityManifest $PrivateAppIdentityManifest}catch{$rejection=$_.Exception.Message}
    if($rejection -cne $reason){throw "Build-reader case $case did not reach its intended rejection: $rejection"}
    $admissionCases.Add($case)
}
$tools=@('bin/java.exe','bin/javac.exe','release','lib/modules')|ForEach-Object {Pin (Join-Path $JavaHome $_)}
$sources=@()
foreach($name in @('ExperimentSessionPackagedClosure.java','ConditionAudioContract.java')){
    $rows=@($session.inventories.java_source_files|Where-Object {[IO.Path]::GetFileName($_.path) -ceq $name})
    if($rows.Count -ne 1){throw 'Actual canonical Java source missing/ambiguous.'};$sources+=,$rows[0]
}
$sources+=,(Pin (Join-Path $PSScriptRoot 'session-core/tests/SpatialSessionClosureHostTest.java'))
$processes=[Collections.Generic.List[object]]::new()
function Run([string]$Label,[string]$Executable,[string[]]$Arguments){
    $start=[Diagnostics.ProcessStartInfo]::new($Executable);$start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($argument in $Arguments){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start;$at=[DateTimeOffset]::UtcNow
    if(-not $process.Start()){throw 'Child failed to start.'}
    $pidValue=$process.Id;$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    $process.WaitForExit();$out=$stdout.GetAwaiter().GetResult();$err=$stderr.GetAwaiter().GetResult();$exit=$process.ExitCode
    [IO.File]::WriteAllText((Join-Path $OutputDirectory ($Label+'.stdout')),$out,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $OutputDirectory ($Label+'.stderr')),$err,[Text.UTF8Encoding]::new($false))
    $processes.Add([ordered]@{label=$Label;executable=(Pin $Executable);arguments=$Arguments;pid=$pidValue;started_at_utc=$at;finished_at_utc=[DateTimeOffset]::UtcNow;exit_code=$exit;process_reaped=$process.HasExited;stdout_eof=$stdout.IsCompletedSuccessfully;stderr_eof=$stderr.IsCompletedSuccessfully})
    $process.Dispose()
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'processes.json'),($processes|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    if($exit -ne 0){throw "Actual $Label failed; retained output must be reviewed."};$out+$err
}
$version=Run 'java-version' (Join-Path $JavaHome 'bin/java.exe') @('-version')
if($version -notmatch 'version "17[.]'){throw 'Explicit JDK17 required.'}
$classes=Join-Path $OutputDirectory 'classes';$null=New-Item -ItemType Directory -Path $classes
$null=Run 'javac' (Join-Path $JavaHome 'bin/javac.exe') (@('-encoding','UTF-8','--release','17','-d',$classes)+@($sources.path))
$lock=Get-Content -LiteralPath $session.feature_lock.path -Raw|ConvertFrom-Json
$identities=Join-Path $OutputDirectory 'asset-identities.tsv'
$rows=@($lock.build_inputs.private_asset_closure.assets|ForEach-Object {$_.logical_destination+"`t"+$_.source_bytes+"`t"+$_.source_sha256})
[IO.File]::WriteAllText($identities,($rows -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
if((Pin (Join-Path $AssetsDirectory 'feature-lock.json')).sha256 -cne $session.feature_lock.sha256){throw 'Test asset lock differs from admitted build lock.'}
$result=Run 'canonical-cases' (Join-Path $JavaHome 'bin/java.exe') @('-cp',$classes,'io.github.mesmerprism.rustyquest.native_renderer.SpatialSessionClosureHostTest',$AssetsDirectory,(Join-Path $OutputDirectory 'materialized'),$session.composition.profile_sha256,$session.composition.provider_manifest_sha256,$session.composition.inventory_sha256,$identities)
foreach($pin in @($tools)+@($sources)){if((Pin $pin.path).sha256 -cne $pin.sha256){throw 'Test source/tool changed during execution.'}}
$receipt=[ordered]@{schema='rusty.quest.spatial_session_closure_host_check.v1';composition=(Pin $SessionCompositionManifest);feature_lock=$session.feature_lock;sources=$sources;tools=$tools;processes=$processes;build_reader_cases=$admissionCases;canonical_cases_passed=5;actual_android_compile=$false;device_evidence=$false}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'host-check.json'),($receipt|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
Write-Output $result
