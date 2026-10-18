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

function Assert-NativeStageSourceOrigin([string]$Origin,[string]$RequestedSource){
    if($Origin-cnotmatch'^[A-Za-z]:[\\/]'-or$RequestedSource-cnotmatch'^[A-Za-z]:[\\/]'){
        throw 'Reusable source origins must be absolute local paths.'
    }
    $originPath=Assert-NativeForestPath $Origin
    $requestedPath=Assert-NativeForestPath $RequestedSource
    if($originPath.Equals($requestedPath,[StringComparison]::OrdinalIgnoreCase)){return}
    # Alternate materializations must be real local worktrees of one repository.
    # Matching remote URLs or byte-equal independent clones do not grant reuse.
    $common=@(foreach($root in @($originPath,$requestedPath)){
        if(-not(Test-Path -LiteralPath $root -PathType Container)){throw 'Alternate reusable source worktree is absent.'}
        $entry=Join-Path $root '.git'
        if(-not(Test-Path -LiteralPath $entry)-or((Get-Item -LiteralPath $entry -Force).Attributes-band[IO.FileAttributes]::ReparsePoint)){
            throw 'Alternate reusable source must have an ordinary local Git entry.'
        }
        $top=[IO.Path]::GetFullPath((Invoke-StageGit $root @('rev-parse','--show-toplevel')))
        if(-not$top.Equals($root,[StringComparison]::OrdinalIgnoreCase)){throw 'Alternate reusable source is not a worktree root.'}
        $gitCommon=Invoke-StageGit $root @('rev-parse','--path-format=absolute','--git-common-dir')
        if($gitCommon-cnotmatch'^[A-Za-z]:[\\/]'-or-not(Test-Path -LiteralPath $gitCommon -PathType Container)){throw 'Local Git common directory is unavailable.'}
        Assert-NativeForestPath $gitCommon
    })
    if(-not$common[0].Equals($common[1],[StringComparison]::OrdinalIgnoreCase)){throw 'Reusable source origin belongs to another Git repository.'}
}

function Sync-ContentStableCargoStage([string]$Original,[string]$Stage,[string]$Commit,[string]$Tree,[IO.FileStream]$NativeSourcesLock){
    # Source and shell share this exact forest. Generated files belong in the
    # caller's separately declared caches/artifact roots, never in this checkout.
    # The caller retains the lock through packaging and final source proofs.
    $originalFull=Assert-NativeForestPath $Original
    $stageFull=Assert-NativeForestPath $Stage
    Assert-NativeForestLock (Split-Path -Parent $stageFull) $NativeSourcesLock
    if($Commit-cnotmatch'^[0-9a-f]{40}$'-or$Tree-cnotmatch'^[0-9a-f]{40}$'){throw 'Exact reusable candidate commit/tree is required.'}
    if($stageFull.Equals($originalFull,[StringComparison]::OrdinalIgnoreCase)-or
       $stageFull.StartsWith($originalFull+'\',[StringComparison]::OrdinalIgnoreCase)-or
       $originalFull.StartsWith($stageFull+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Reusable source overlaps its supplier.'}
    $Original=$originalFull;$Stage=$stageFull
    if(-not[IO.Path]::GetFullPath((Invoke-StageGit $Original @('rev-parse','--show-toplevel'))).Equals($Original,[StringComparison]::OrdinalIgnoreCase)){throw 'Reusable supplier must be a Git worktree root.'}
    if((Invoke-StageGit $Original @('rev-parse','HEAD'))-cne$Commit-or
       (Invoke-StageGit $Original @('rev-parse','HEAD^{tree}'))-cne$Tree-or
       (Invoke-StageGit $Original @('status','--porcelain=v1','-z','--untracked-files=all')).Length){throw 'Reusable supplier candidate identity differs.'}
    $stageExisted=Test-Path -LiteralPath $Stage
    if($stageExisted){
        Assert-CleanNativeStage $Stage
        if(-not[IO.Path]::GetFullPath((Invoke-StageGit $Stage @('rev-parse','--show-toplevel'))).Equals($Stage,[StringComparison]::OrdinalIgnoreCase)){throw 'Reusable stage must be a Git worktree root.'}
        $origin=Invoke-StageGit $Stage @('remote','get-url','origin')
        Assert-NativeStageSourceOrigin $origin $Original
        $null=Invoke-StageGit $Stage @('fetch','--no-tags','--',$Original,$Commit)
    }else{
        $null=Invoke-StageGit (Split-Path -Parent $Stage) @('-c','core.autocrlf=false','clone','--no-hardlinks','--no-checkout','--no-tags','--',$Original,$Stage)
    }
    # Avoid even a same-commit checkout: Git must not refresh unchanged raw source
    # timestamps merely because a new per-attempt proof was requested.
    if(-not$stageExisted-or(Invoke-StageGit $Stage @('rev-parse','--verify','HEAD'))-cne$Commit){
        $null=Invoke-StageGit $Stage @('checkout','--detach',$Commit)
    }elseif((Invoke-StageGit $Stage @('rev-parse','HEAD^{tree}'))-cne$Tree){throw 'Reusable candidate tree differs.'}
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
    Assert-NativeForestLock (Split-Path -Parent $Stage) $NativeSourcesLock
    Assert-CleanNativeStage $Stage
}

Export-ModuleMember -Function Write-StageBytesIfDifferent,Sync-ContentStableCargoStage,Assert-NativeStageSourceOrigin

function Assert-NativeForestPath([string]$Path){
    $full=[IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\','/'))
    if($full-eq[IO.Path]::GetPathRoot($full).TrimEnd([char[]]@('\','/'))){throw 'Native forest cannot be a filesystem root.'}
    $cursor=$full
    while($cursor){
        if((Test-Path -LiteralPath $cursor)-and((Get-Item -LiteralPath $cursor -Force).Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Native forest ancestors cannot be links.'}
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
    $entries=@(Get-ChildItem -LiteralPath $Root -Force -Recurse)
    if(@($entries|Where-Object{$_.Attributes-band[IO.FileAttributes]::ReparsePoint}).Count){throw 'Native source contains linked payload; retain it.'}
    # Do not use --exclude-standard: ignored compiler/tool/artifact leftovers are
    # also forbidden. The source graph never owns their cleanup or retirement.
    if((Invoke-StageGit $Root @('ls-files','--others','-z')).Length){throw 'Native stage contains generated or unexpected payload; retain it.'}
    if((Invoke-StageGit $Root @('status','--porcelain=v1','-z','--untracked-files=all')).Length){throw 'Native stage tracked inputs changed.'}
    # Git does not report empty directories. Refuse those leftovers too rather
    # than silently treating a previous failed output directory as source.
    $sourceDirectories=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $tracked=(Invoke-StageGit $Root @('ls-files','-z')).Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)
    foreach($path in $tracked){
        $parent=Split-Path -Parent $path
        while($parent){[void]$sourceDirectories.Add($parent.Replace('\','/'));$parent=Split-Path -Parent $parent}
    }
    foreach($entry in $entries|Where-Object PSIsContainer){
        $relative=[IO.Path]::GetRelativePath($Root,$entry.FullName).Replace('\','/')
        if($relative-ceq'.git'-or$relative.StartsWith('.git/',[StringComparison]::Ordinal)){continue}
        if(-not$sourceDirectories.Contains($relative)){throw 'Native stage contains an undeclared directory; retain it.'}
    }
}

Export-ModuleMember -Function Open-NativeForestLock,Assert-NativeForestLock,Assert-CleanNativeStage,Assert-NativeForestPath

