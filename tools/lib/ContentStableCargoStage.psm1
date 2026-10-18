Set-StrictMode -Version Latest

function Write-StageBytesIfDifferent([string]$Source,[string]$Destination){
    $sourceBytes=[IO.File]::ReadAllBytes($Source)
    if(Test-Path -LiteralPath $Destination -PathType Leaf){
        $destinationBytes=[IO.File]::ReadAllBytes($Destination)
        if([Linq.Enumerable]::SequenceEqual[byte]($sourceBytes,$destinationBytes)){return}
    }
    [IO.File]::WriteAllBytes($Destination,$sourceBytes)
}

function Invoke-StageGit([string]$Root,[string[]]$Arguments){
    $value=(& git.exe -c core.longpaths=true -C $Root @Arguments 2>&1)-join "`n"
    if($LASTEXITCODE-ne0){throw "Stage Git operation failed: $value"}
    return $value
}

function Sync-ContentStableCargoStage([string]$Original,[string]$Stage,[string]$Commit,[string]$Tree){
    # The caller holds the stable forest's exclusive OS lock through its complete
    # build. No previous receipt or timestamp substitutes for fresh source proofs.
    if(Test-Path -LiteralPath $Stage){
        if((Get-Item -LiteralPath $Stage).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Reusable source root is a link.'}
        if(@(Get-ChildItem -LiteralPath $Stage -Force -Recurse|Where-Object{$_.Attributes-band[IO.FileAttributes]::ReparsePoint}).Count){throw 'Reusable source contains linked payload; retain it.'}
        $origin=Invoke-StageGit $Stage @('remote','get-url','origin')
        if([IO.Path]::GetFullPath($origin)-cne[IO.Path]::GetFullPath($Original)){throw 'Reusable source origin differs.'}
        if((Invoke-StageGit $Stage @('status','--porcelain=v1','-z','--untracked-files=no')).Length){throw 'Reusable tracked source is dirty.'}
        # Prototype deliberately rejects unknown leftovers, including ignored
        # files. Actual owned generated-output cleanup needs a precise caller seam.
        if((Invoke-StageGit $Stage @('ls-files','--others','-z')).Length){throw 'Reusable source contains undeclared generated or untracked files; retain it.'}
        $null=Invoke-StageGit $Stage @('fetch','--no-tags','--',$Original,$Commit)
    }else{
        $null=Invoke-StageGit (Split-Path -Parent $Stage) @('-c','core.autocrlf=false','clone','--no-hardlinks','--no-checkout','--no-tags','--',$Original,$Stage)
    }
    $null=Invoke-StageGit $Stage @('checkout','--detach',$Commit)
    $paths=(Invoke-StageGit $Original @('ls-files','-z')).Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)
    foreach($path in $paths){Write-StageBytesIfDifferent (Join-Path $Original $path) (Join-Path $Stage $path)}
    foreach($key in @('core.autocrlf','core.eol','core.safecrlf')){
        $default=switch($key){'core.autocrlf'{'false'};'core.eol'{'native'};default{'false'}}
        $value=Invoke-StageGit $Original @('config','--get','--default',$default,$key)
        $null=Invoke-StageGit $Stage @('config','--local',$key,$value)
    }
    $null=Invoke-StageGit $Stage @('add','--renormalize','--','.')
    if((Invoke-StageGit $Stage @('write-tree'))-cne$Tree-or
       (Invoke-StageGit $Stage @('rev-parse','HEAD'))-cne$Commit-or
       (Invoke-StageGit $Stage @('status','--porcelain=v1','-z','--untracked-files=all')).Length){throw 'Exact staged candidate identity differs.'}
    foreach($path in $paths){
        if((Get-FileHash (Join-Path $Original $path)).Hash-cne(Get-FileHash (Join-Path $Stage $path)).Hash){throw 'Exact staged raw bytes differ.'}
    }
}

Export-ModuleMember -Function Write-StageBytesIfDifferent,Sync-ContentStableCargoStage

function Assert-NativeForestPath([string]$Path){
    $full=[IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\','/'))
    if($full-eq[IO.Path]::GetPathRoot($full).TrimEnd([char[]]@('\','/'))){throw 'Native forest cannot be a filesystem root.'}
    $cursor=$full
    while($cursor){
        if((Test-Path -LiteralPath $cursor)-and((Get-Item -LiteralPath $cursor).Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Native forest ancestors cannot be links.'}
        $cursor=Split-Path -Parent $cursor
    }
    return $full
}

function Open-NativeForestLock([string]$CacheRoot){
    $cache=Assert-NativeForestPath $CacheRoot
    $forest=Join-Path $cache 'n'
    if(-not(Test-Path -LiteralPath $cache -PathType Container)){throw 'Declared build cache must already exist.'}
    $lockPath=Join-Path $cache 'native-source.lock'
    if((Test-Path -LiteralPath $lockPath)-and((Get-Item -LiteralPath $lockPath).Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Native source lock cannot be a link.'}
    # Parent operation retains this exclusive handle through metadata, host tools,
    # native Cargo, shell packaging and final source authentication. No cleanup.
    [IO.File]::Open($lockPath,'OpenOrCreate','ReadWrite','None')
}

function Assert-NativeForestLock([string]$Forest,[IO.FileStream]$Lock){
    $full=Assert-NativeForestPath $Forest
    $expected=Join-Path (Split-Path -Parent $full) 'native-source.lock'
    if((Split-Path -Leaf $full)-cne'n'-or$null-eq$Lock-or-not$Lock.CanWrite-or$Lock.SafeFileHandle.IsClosed-or
       -not[IO.Path]::GetFullPath($Lock.Name).Equals($expected,[StringComparison]::OrdinalIgnoreCase)){throw 'Exact live native forest lock is required.'}
    # A handle opened with permissive sharing is not an ownership witness.
    $probe=$null
    try{$probe=[IO.File]::Open($expected,'Open','ReadWrite','ReadWrite')}catch [IO.IOException]{return}
    finally{if($probe){$probe.Dispose()}}
    throw 'Native forest lock is not exclusive.'
}

function Assert-CleanNativeStage([string]$Root){
    $null=Assert-NativeForestPath $Root
    if(@(Get-ChildItem -LiteralPath $Root -Force -Recurse|Where-Object{$_.Attributes-band[IO.FileAttributes]::ReparsePoint}).Count){throw 'Native source contains linked payload; retain it.'}
    if((Invoke-StageGit $Root @('ls-files','--others','-z')).Length){throw 'Native stage contains generated or unexpected payload; retain it.'}
    if((Invoke-StageGit $Root @('status','--porcelain=v1','-z','--untracked-files=all')).Length){throw 'Native stage tracked inputs changed.'}
}

Export-ModuleMember -Function Open-NativeForestLock,Assert-NativeForestLock,Assert-CleanNativeStage,Assert-NativeForestPath

