[CmdletBinding()]
param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot ".."),
    [ValidateSet('Resolve', 'VerifyCache')][string]$Mode = 'Resolve',
    [switch]$SelfTest,
    [ValidateRange(1, 55)][int]$TimeoutSeconds = 55,
    [Parameter(DontShow)][switch]$InternalSelfTestWorker,
    [Parameter(DontShow)][string]$InternalSelfTestRoot,
    [Parameter(DontShow)][string]$InternalSelfTestFixture
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$script:ExpectedVersion = '9.4.1'
$script:ExpectedSha256 = '2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb'
$script:ExpectedTreeSha256 = '5c94e8204be25e0c18c94780cf4cf768fefa92e73f8c7b1617c483f33ca088db'
$script:ArchiveName = 'gradle-9.4.1-bin.zip'
$script:SelfTestObservedPart = ''

function Get-Sha256 { param([Parameter(Mandatory)][string]$Path) (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-FullPath { param([Parameter(Mandatory)][string]$Path) [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\', '/')) }
function Test-Reparse { param([Parameter(Mandatory)][string]$Path) (Test-Path -LiteralPath $Path) -and (((Get-Item -LiteralPath $Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) }
function Assert-NotReparse { param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][string]$Label) if (Test-Reparse $Path) { throw "$Label must not be a reparse point: $Path" } }
function Assert-Under { param([Parameter(Mandatory)][string]$Root,[Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][string]$Label) $r=Get-FullPath $Root;$p=Get-FullPath $Path;if(-not $p.StartsWith($r+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw "$Label escapes its root: $Path"};$p }

function Initialize-WindowsFileInfo {
    if ('RustyQuestGradleNative' -as [type]) { return }
    Add-Type @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class RustyQuestGradleNative {
 [StructLayout(LayoutKind.Sequential)] public struct Info { public uint Attributes, CreationLow, CreationHigh, AccessLow, AccessHigh, WriteLow, WriteHigh, Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow; }
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern SafeFileHandle CreateFile(string n,uint a,uint s,IntPtr q,uint d,uint f,IntPtr t);
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GetFileInformationByHandle(SafeFileHandle h,out Info i);
 public static string Evidence(string path) { using(var h=CreateFile(path,0,7,IntPtr.Zero,3,0x02000000,IntPtr.Zero)) { if(h.IsInvalid) return null; Info i; if(!GetFileInformationByHandle(h,out i)) return null; return i.Volume.ToString("x8")+":"+i.IndexHigh.ToString("x8")+i.IndexLow.ToString("x8")+":"+i.Links.ToString(); } }
 public static int Links(string path) { using(var h=CreateFile(path,0,7,IntPtr.Zero,3,0x02000000,IntPtr.Zero)) { if(h.IsInvalid) return -1; Info i; if(!GetFileInformationByHandle(h,out i)) return -1; return (int)i.Links; } }
}
'@
}
function Get-PathEvidence { param([Parameter(Mandatory)][string]$Path) try { Initialize-WindowsFileInfo; [RustyQuestGradleNative]::Evidence($Path) } catch { $null } }
function Assert-SameEvidence { param([string]$Expected,[Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][string]$Label) if($null -ne $Expected -and (Get-PathEvidence $Path) -cne $Expected){throw "$Label changed during resolver operation: $Path"} }
function Assert-NoHardLink { param([Parameter(Mandatory)][string]$Path) try { Initialize-WindowsFileInfo; $links=[RustyQuestGradleNative]::Links($Path); if($links -gt 1){throw "Resolver refuses hardlinked cache file: $Path"} } catch { if($_.Exception.Message -like 'Resolver refuses*'){throw} } }
function Get-ResolverMutexName {
    param([Parameter(Mandatory)][string]$StableDirectory)
    $evidence=Get-PathEvidence $StableDirectory
    # File-ID evidence is canonical across Windows path spelling/case aliases. If it is unavailable,
    # Windows' case-insensitive canonical full path remains a safe non-reparse fallback for this host.
    $key=if($evidence){"fileid:$evidence"}else{"fallback:$((Get-FullPath $StableDirectory).ToUpperInvariant())"}
    $digest=([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes("$key|gradle|$script:ExpectedVersion"))|ForEach-Object ToString x2)-join ''
    'Local\RustyQuestGradle941-'+$digest.Substring(0,24)
}

function Assert-SafeTree {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][string]$Label)
    Assert-NotReparse $Path $Label
    $q=[Collections.Generic.Queue[string]]::new();$q.Enqueue($Path)
    while($q.Count){$d=$q.Dequeue();foreach($item in @(Get-ChildItem -LiteralPath $d -Force)){Assert-NotReparse $item.FullName $Label;if($item.PSIsContainer){$q.Enqueue($item.FullName)}else{Assert-NoHardLink $item.FullName}}}
}
function New-SafeDirectory {
    param([Parameter(Mandatory)][string]$Root,[Parameter(Mandatory)][string]$Relative,[Parameter(Mandatory)][string]$Label,[switch]$Create)
    if([IO.Path]::IsPathRooted($Relative)-or $Relative -match '(^|[\\/])\.\.([\\/]|$)'){throw "$Label must be a safe relative path."}
    $current=Get-FullPath $Root;Assert-NotReparse $current 'repository root'
    foreach($segment in ($Relative -split '[\\/]')){if([string]::IsNullOrWhiteSpace($segment)-or $segment -in @('.','..')){throw "$Label has an invalid segment."};$current=Join-Path $current $segment;if(Test-Path -LiteralPath $current){Assert-NotReparse $current $Label;if(-not(Test-Path -LiteralPath $current -PathType Container)){throw "$Label is not a directory: $current"}}elseif($Create){New-Item -ItemType Directory -Path $current -ErrorAction Stop|Out-Null;Assert-NotReparse $current $Label}else{throw "$Label is absent: $current"}}
    Get-FullPath $current
}
function Read-Identity {
    param([Parameter(Mandatory)][string]$Repo)
    $path=Join-Path $Repo 'config\gradle-9.4.1-tool.json';if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Pinned Gradle identity is missing: $path"};Assert-NotReparse $path 'Pinned Gradle identity';Assert-NoHardLink $path
    $i=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($i.schema -cne 'rusty.quest.tool_identity.v1' -or $i.tool_id -cne 'gradle' -or $i.version -cne $script:ExpectedVersion -or $i.sha256 -cne $script:ExpectedSha256 -or $i.tree_sha256 -cne $script:ExpectedTreeSha256){throw 'Pinned Gradle identity version, SHA-256, or tree identity drifted.'}
    if($i.archive_file_name -cne $script:ArchiveName -or $i.expected_top_level_directory -cne 'gradle-9.4.1'){throw 'Pinned Gradle archive/layout identity drifted.'}
    foreach($url in @($i.distribution_url,$i.official_checksum_url)){try{$u=[Uri][string]$url}catch{throw 'Pinned Gradle URL is invalid.'};if(-not $u.IsAbsoluteUri -or $u.Scheme -cne 'https' -or $u.Host -cne 'services.gradle.org' -or $u.Query -or $u.Fragment){throw 'Pinned Gradle URL must be canonical official HTTPS.'}}
    if(@($i.required_relative_paths)-notcontains 'bin/gradle.bat' -or @($i.required_relative_paths)-notcontains 'lib/gradle-launcher-9.4.1.jar'){throw 'Pinned Gradle required layout drifted.'}
    $i
}
function Get-NormalArchivePath {
    param([Parameter(Mandatory)][string]$Name,[Parameter(Mandatory)]$Identity)
    if([string]::IsNullOrWhiteSpace($Name)-or $Name.Length -gt [int]$Identity.max_path_chars -or $Name -match '[\\:\x00-\x1f\x7f-\x9f]' -or $Name.StartsWith('/') -or $Name.StartsWith('//')){throw "Unsafe archive path: $Name"}
    $parts=$Name.TrimEnd('/') -split '/';if($parts.Count -gt [int]$Identity.max_path_depth){throw "Archive path is too deep: $Name"}
    foreach($part in $parts){$base=($part -split '\.')[0];if([string]::IsNullOrEmpty($part)-or $part -in @('.','..') -or $part.EndsWith('.') -or $part.EndsWith(' ') -or $base -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])$'){throw "Unsafe Windows archive segment: $Name"}}
    $Name.ToLowerInvariant()
}
function Get-ArchiveTree {
    param([Parameter(Mandatory)][string]$Archive,[Parameter(Mandatory)]$Identity)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($Archive)
    try{
        if($zip.Entries.Count -gt [int]$Identity.max_entries){throw 'Gradle archive entry limit exceeded.'}
        $prefix="$($Identity.expected_top_level_directory)/";$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase);$dirs=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase);$files=@();[int64]$total=0
        foreach($entry in $zip.Entries){$name=[string]$entry.FullName;$key=Get-NormalArchivePath $name $Identity;if(-not $seen.Add($key)){throw "Duplicate Windows-normalized archive path: $name"};if(-not $name.StartsWith($prefix,[StringComparison]::Ordinal)){throw "Unexpected archive top-level path: $name"};if(($entry.ExternalAttributes -shr 16 -band 0xF000) -eq 0xA000){throw "Archive symlink is forbidden: $name"};$relative=$name.Substring($prefix.Length).TrimEnd('/');if($relative){$parts=$relative -split '/';for($n=1;$n -lt $parts.Count;$n++){[void]$dirs.Add(($parts[0..($n-1)] -join '/'))};if($name.EndsWith('/')){[void]$dirs.Add($relative)}else{$total += [int64]$entry.Length;if($total -gt [int64]$Identity.max_extract_bytes){throw 'Gradle archive uncompressed size limit exceeded.'};$sha=[Security.Cryptography.SHA256]::Create();try{$s=$entry.Open();try{$buffer=New-Object byte[] 65536;while(($read=$s.Read($buffer,0,$buffer.Length)) -gt 0){[void]$sha.TransformBlock($buffer,0,$read,$buffer,0)};[void]$sha.TransformFinalBlock([byte[]]@(),0,0);$hash=([BitConverter]::ToString($sha.Hash)).Replace('-','').ToLowerInvariant()}finally{$s.Dispose()}}finally{$sha.Dispose()};$files += [ordered]@{path=$relative;bytes=[int64]$entry.Length;sha256=$hash}}}
        }
        foreach($required in @($Identity.required_relative_paths)){if(@($files.path) -notcontains ([string]$required)){throw "Archive lacks required file: $required"}}
        $tree=[ordered]@{schema='rusty.quest.gradle_tree.v1';tool_id='gradle';version=$Identity.version;archive_sha256=(Get-Sha256 $Archive);directories=@($dirs|Sort-Object);files=@($files|Sort-Object path)};$canonical=$tree|ConvertTo-Json -Depth 8 -Compress;$tree.tree_sha256=([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))|ForEach-Object ToString x2)-join '';return [pscustomobject]$tree
    }finally{$zip.Dispose()}
}
function Test-InstalledTree {
    param([Parameter(Mandatory)][string]$InstallRoot,[Parameter(Mandatory)]$Tree)
    if(-not(Test-Path -LiteralPath $InstallRoot -PathType Container)){return $false};Assert-SafeTree $InstallRoot 'installed Gradle tree';$actualFiles=@();$actualDirs=@();$q=[Collections.Generic.Queue[string]]::new();$q.Enqueue($InstallRoot)
    while($q.Count){$d=$q.Dequeue();foreach($item in @(Get-ChildItem -LiteralPath $d -Force)){Assert-NotReparse $item.FullName 'installed Gradle tree';$relative=$item.FullName.Substring($InstallRoot.Length).TrimStart([char[]]@('\','/')).Replace('\','/');if($item.PSIsContainer){$actualDirs += $relative;$q.Enqueue($item.FullName)}else{Assert-NoHardLink $item.FullName;$actualFiles += [pscustomobject]@{path=$relative;bytes=[int64]$item.Length;sha256=(Get-Sha256 $item.FullName)}}}}
    if(@(Compare-Object @($Tree.directories) @($actualDirs|Sort-Object) -SyncWindow 0).Count){return $false};$expected=@($Tree.files|ForEach-Object{"$($_.path)|$($_.bytes)|$($_.sha256)"})|Sort-Object;$actual=@($actualFiles|ForEach-Object{"$($_.path)|$($_.bytes)|$($_.sha256)"})|Sort-Object;(@(Compare-Object $expected $actual -SyncWindow 0).Count -eq 0)
}
function Write-AtomicJson {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)]$Value,[Parameter(Mandatory)][string]$Parent)
    Assert-NotReparse $Parent 'resolver parent';$canonical=$Value|ConvertTo-Json -Depth 10 -Compress;if(Test-Path -LiteralPath $Path){Assert-NotReparse $Path 'immutable resolver record';Assert-NoHardLink $Path;$existing=Get-Content -LiteralPath $Path -Raw|ConvertFrom-Json|ConvertTo-Json -Depth 10 -Compress;if($existing -cne $canonical){throw "Immutable resolver record conflicts: $Path"};return};$tmp=Join-Path $Parent (".$([IO.Path]::GetFileName($Path)).$([Guid]::NewGuid().ToString('N')).tmp");try{[IO.File]::WriteAllText($tmp,$canonical,[Text.UTF8Encoding]::new($false));Assert-NoHardLink $tmp;Assert-NotReparse $Parent 'resolver parent';[IO.File]::Move($tmp,$Path,$false)}finally{if(Test-Path -LiteralPath $tmp){Remove-Item -LiteralPath $tmp -Force}}
}
function Invoke-Download {
    param([Parameter(Mandatory)][Uri]$Uri,[Parameter(Mandatory)][string]$Part,[Parameter(Mandatory)]$Identity,[int]$Timeout,[switch]$Fail)
    if($Fail){[IO.File]::WriteAllText($Part,'simulated-provider-partial');$script:SelfTestObservedPart=$Part;if(-not(Test-Path -LiteralPath $Part -PathType Leaf) -or [IO.Path]::GetFileName($Part) -notmatch '^\.gradle-9\.4\.1-bin\.zip\.[0-9a-f]{32}\.part$'){throw 'Simulated provider did not create the expected GUID temporary archive.'};throw 'Simulated Gradle provider failure.'};$h=[Net.Http.HttpClientHandler]::new();$h.AllowAutoRedirect=$false;$h.SslProtocols=[Security.Authentication.SslProtocols]::Tls12 -bor [Security.Authentication.SslProtocols]::Tls13;$c=[Net.Http.HttpClient]::new($h);$c.Timeout=[TimeSpan]::FromSeconds($Timeout);$chain=@();try{$u=$Uri;for($n=0;$n -le 3;$n++){if($u.Scheme -cne 'https' -or @($Identity.allowed_redirect_hosts) -notcontains $u.Host.ToLowerInvariant()){throw "Gradle redirect violates HTTPS allow-list: $($u.GetLeftPart([UriPartial]::Path))"};$chain += $u.GetLeftPart([UriPartial]::Path);$r=$c.GetAsync($u,[Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult();if([int]$r.StatusCode -in 301,302,303,307,308){$l=$r.Headers.Location;$r.Dispose();if($null -eq $l){throw 'Gradle redirect lacks Location.'};$u=if($l.IsAbsoluteUri){$l}else{[Uri]::new($u,$l)};continue};if(-not $r.IsSuccessStatusCode){$s=[int]$r.StatusCode;$r.Dispose();throw "Gradle provider returned HTTP $s."};if($r.Content.Headers.ContentLength -and [int64]$r.Content.Headers.ContentLength -gt [int64]$Identity.max_archive_bytes){$r.Dispose();throw 'Gradle response exceeds byte bound.'};$input=$r.Content.ReadAsStream();$out=[IO.FileStream]::new($Part,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);try{$buffer=New-Object byte[] 65536;[int64]$bytes=0;while(($read=$input.Read($buffer,0,$buffer.Length)) -gt 0){$bytes += $read;if($bytes -gt [int64]$Identity.max_archive_bytes){throw 'Gradle response exceeds byte bound.'};$out.Write($buffer,0,$read)};$out.Flush($true)}finally{$out.Dispose();$input.Dispose()};$headers=[ordered]@{etag=[string]$r.Headers.ETag;last_modified=[string]$r.Content.Headers.LastModified;content_length=[string]$r.Content.Headers.ContentLength;content_type=[string]$r.Content.Headers.ContentType};$r.Dispose();return [pscustomobject]@{uri_chain=$chain;bytes=$bytes;headers=$headers}}
        throw 'Gradle redirect limit exceeded.'}finally{$c.Dispose();$h.Dispose()}
}
function Invoke-Resolver {
    param([Parameter(Mandatory)][string]$Repo,[ValidateSet('Resolve','VerifyCache')][string]$RequestedMode,[int]$Timeout,[object]$TestIdentity=$null,[string]$Fixture='',[switch]$ProviderFailure)
    $root=Get-FullPath $Repo;if(-not(Test-Path -LiteralPath $root -PathType Container)){throw "RepoRoot is missing: $root"};Assert-NotReparse $root 'RepoRoot';$identity=if($null -eq $TestIdentity){Read-Identity $root}else{$TestIdentity};if($identity.tool_id -cne 'gradle' -or $identity.version -cne $script:ExpectedVersion -or [string]$identity.sha256 -notmatch '^[a-f0-9]{64}$' -or [string]$identity.tree_sha256 -notmatch '^[a-f0-9]{64}$' -or -not $identity.max_path_chars -or -not $identity.max_path_depth){throw 'Resolver identity fixture is malformed.'};$downloads=New-SafeDirectory $root 'local-artifacts\downloads' 'downloads root' -Create:($RequestedMode -eq 'Resolve');$tools=New-SafeDirectory $root 'local-artifacts\tools' 'tools root' -Create:($RequestedMode -eq 'Resolve');$archive=Join-Path $downloads $script:ArchiveName;$gradleHome=Join-Path $tools 'gradle-9.4.1';[void](Assert-Under $downloads $archive 'archive');[void](Assert-Under $tools $gradleHome 'install');$mutex=[Threading.Mutex]::new($false,(Get-ResolverMutexName $root));$held=$false
    try{
        $held=$mutex.WaitOne([TimeSpan]::FromSeconds($Timeout));if(-not $held){throw 'Timed out waiting for Gradle resolver lock.'}
        if(-not(Test-Path -LiteralPath $archive -PathType Leaf)){if($RequestedMode -eq 'VerifyCache'){throw 'Pinned Gradle archive cache is absent.'};$valid=$false}else{Assert-NotReparse $archive 'archive';Assert-NoHardLink $archive;$valid=((Get-Item -LiteralPath $archive).Length -le [int64]$identity.max_archive_bytes -and (Get-Sha256 $archive) -ceq [string]$identity.sha256)}
        if(-not $valid){
            if($RequestedMode -eq 'VerifyCache'){throw 'Pinned Gradle archive cache is corrupt.'}
            $retiredArchive=$null;$downloadParentEvidence=Get-PathEvidence $downloads
            if(Test-Path -LiteralPath $archive){
                $archiveEvidence=Get-PathEvidence $archive;Assert-SameEvidence $downloadParentEvidence $downloads 'downloads root';Assert-SameEvidence $archiveEvidence $archive 'archive';Assert-NotReparse $archive 'archive';Assert-NoHardLink $archive
                $retiredArchive=Join-Path $downloads (".$script:ArchiveName.retired.$([Guid]::NewGuid().ToString('N'))");[IO.File]::Move($archive,$retiredArchive,$false);Assert-SameEvidence $downloadParentEvidence $downloads 'downloads root';Assert-SameEvidence $archiveEvidence $retiredArchive 'retired archive'
            }
            $part=Join-Path $downloads (".$script:ArchiveName.$([Guid]::NewGuid().ToString('N')).part")
            try {
                $meta=if($Fixture){Copy-Item -LiteralPath $Fixture -Destination $part -ErrorAction Stop;[pscustomobject]@{uri_chain=@('fixture://offline/gradle-9.4.1-bin.zip');bytes=(Get-Item $part).Length;headers=@{}}}else{Invoke-Download ([Uri]$identity.distribution_url) $part $identity $Timeout -Fail:$ProviderFailure}
                Assert-NotReparse $part 'Gradle download temporary';Assert-NoHardLink $part;if((Get-Sha256 $part) -cne [string]$identity.sha256){throw 'Downloaded Gradle archive hash mismatch.'}
                Assert-SameEvidence $downloadParentEvidence $downloads 'downloads root';[IO.File]::Move($part,$archive,$false);Assert-SameEvidence $downloadParentEvidence $downloads 'downloads root'
                if($retiredArchive){Assert-SameEvidence $archiveEvidence $retiredArchive 'retired archive';Assert-NoHardLink $retiredArchive;Remove-Item -LiteralPath $retiredArchive -Force;Assert-SameEvidence $downloadParentEvidence $downloads 'downloads root'}
            } catch {
                if($retiredArchive -and -not(Test-Path -LiteralPath $archive) -and (Test-Path -LiteralPath $retiredArchive)){Assert-SameEvidence $downloadParentEvidence $downloads 'downloads root';Assert-SameEvidence $archiveEvidence $retiredArchive 'retired archive';[IO.File]::Move($retiredArchive,$archive,$false)}
                throw
            } finally {if(Test-Path -LiteralPath $part){Assert-NotReparse $part 'Gradle download temporary';Assert-NoHardLink $part;Remove-Item -LiteralPath $part -Force}}
            Write-AtomicJson "$archive.download.$($identity.sha256).json" ([ordered]@{schema='rusty.quest.tool_download_record.v2';tool_id='gradle';version=$identity.version;archive_sha256=$identity.sha256;tree_sha256=$identity.tree_sha256;bytes=$meta.bytes;uri_chain=$meta.uri_chain;headers=$meta.headers}) $downloads
        }
        $treeFiles=@(Get-ChildItem -LiteralPath $downloads -File -Filter "$script:ArchiveName.tree.*.json")
        $tree=Get-ArchiveTree $archive $identity;if($tree.tree_sha256 -cne [string]$identity.tree_sha256){throw 'Verified Gradle archive tree digest drifted from the repository-pinned identity.'};$treePath="$archive.tree.$($tree.tree_sha256).json";if($treeFiles.Count -eq 0){if($RequestedMode -eq 'VerifyCache'){throw 'Verified Gradle tree manifest is absent.'};Write-AtomicJson $treePath $tree $downloads}else{if($treeFiles.Count -ne 1 -or $treeFiles[0].FullName -cne $treePath){throw 'Pinned Gradle cache has ambiguous tree manifests.'};Assert-NotReparse $treePath 'verified Gradle tree manifest';Assert-NoHardLink $treePath;$recorded=Get-Content -LiteralPath $treePath -Raw|ConvertFrom-Json;if(($recorded|ConvertTo-Json -Depth 10 -Compress) -cne ($tree|ConvertTo-Json -Depth 10 -Compress)){throw 'Verified Gradle tree manifest drifted from the pinned archive.'}}
        if(-not(Test-InstalledTree $gradleHome $tree)){
            if($RequestedMode -eq 'VerifyCache'){throw 'Pinned Gradle installation is absent or corrupt.'}
            $stage=Join-Path $tools (".gradle-9.4.1.stage.$([Guid]::NewGuid().ToString('N'))");$retired=$null
            try {
                New-Item -ItemType Directory -Path $stage|Out-Null;Assert-NotReparse $stage 'Gradle staging'
                [IO.Compression.ZipFile]::ExtractToDirectory($archive,$stage)
                $staged=Join-Path $stage 'gradle-9.4.1';if(-not(Test-InstalledTree $staged $tree)){throw 'Extracted Gradle tree is not the verified archive tree.'}
                $parentEvidence=Get-PathEvidence $tools;Assert-SameEvidence $parentEvidence $tools 'tools root'
                if(Test-Path -LiteralPath $gradleHome){
                    $targetEvidence=Get-PathEvidence $gradleHome;Assert-SameEvidence $targetEvidence $gradleHome 'Gradle install';Assert-SafeTree $gradleHome 'Gradle install'
                    $retired=Join-Path $tools (".gradle-9.4.1.retired.$([Guid]::NewGuid().ToString('N'))")
                    [IO.Directory]::Move($gradleHome,$retired);Assert-SameEvidence $parentEvidence $tools 'tools root';Assert-SameEvidence $targetEvidence $retired 'retired Gradle install'
                }
                Assert-SameEvidence $parentEvidence $tools 'tools root';[IO.Directory]::Move($staged,$gradleHome);Assert-SameEvidence $parentEvidence $tools 'tools root'
                if(-not(Test-InstalledTree $gradleHome $tree)){throw 'Promoted Gradle tree failed verification.'}
                if($retired){$retiredEvidence=Get-PathEvidence $retired;Assert-SameEvidence $targetEvidence $retired 'retired Gradle install';Assert-SafeTree $retired 'retired Gradle install';Remove-Item -LiteralPath $retired -Recurse -Force;Assert-SameEvidence $parentEvidence $tools 'tools root'}
            } catch {
                if($retired -and -not(Test-Path -LiteralPath $gradleHome) -and (Test-Path -LiteralPath $retired)){Assert-SameEvidence $parentEvidence $tools 'tools root';Assert-SameEvidence $targetEvidence $retired 'retired Gradle install';[IO.Directory]::Move($retired,$gradleHome)}
                throw
            } finally {if(Test-Path -LiteralPath $stage){Assert-SafeTree $stage 'Gradle staging';Remove-Item -LiteralPath $stage -Recurse -Force}}
        }
        if(-not(Test-InstalledTree $gradleHome $tree)){throw 'Promoted Gradle tree failed verification.'};[pscustomobject]@{schema='rusty.quest.gradle_cache_receipt.v2';mode=$RequestedMode;tool_id='gradle';version=$identity.version;archive_sha256=$identity.sha256;tree_sha256=$tree.tree_sha256;gradle_home=$gradleHome;gradle_bat=(Join-Path $gradleHome 'bin\gradle.bat')}
    }finally{if($held){$mutex.ReleaseMutex()};$mutex.Dispose()}
}
function Invoke-SelfTest {
    $root=Join-Path ([IO.Path]::GetTempPath()) ('rusty-quest-gradle-'+[Guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Path (Join-Path $root 'config') -Force|Out-Null
        $source=Join-Path $root 'source\gradle-9.4.1';New-Item -ItemType Directory -Path (Join-Path $source 'bin'),(Join-Path $source 'lib') -Force|Out-Null
        [IO.File]::WriteAllText((Join-Path $source 'bin\gradle.bat'),'@echo off');[IO.File]::WriteAllBytes((Join-Path $source 'lib\gradle-launcher-9.4.1.jar'),[byte[]](1,2,3))
        $fixture=Join-Path $root $script:ArchiveName;[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $root 'source'),$fixture);$h=Get-Sha256 $fixture
        $id=[pscustomobject]@{schema='rusty.quest.tool_identity.v1';tool_id='gradle';version='9.4.1';sha256=$h;tree_sha256='';archive_file_name=$script:ArchiveName;expected_top_level_directory='gradle-9.4.1';required_relative_paths=@('bin/gradle.bat','lib/gradle-launcher-9.4.1.jar');distribution_url='https://services.gradle.org/distributions/gradle-9.4.1-bin.zip';official_checksum_url='https://services.gradle.org/distributions/gradle-9.4.1-bin.zip.sha256';allowed_redirect_hosts=@('services.gradle.org');max_archive_bytes=1048576;max_extract_bytes=1048576;max_entries=20;max_path_chars=512;max_path_depth=20}
        $id.tree_sha256=(Get-ArchiveTree $fixture $id).tree_sha256
        [IO.File]::WriteAllText((Join-Path $root 'config\gradle-9.4.1-tool.json'),($id|ConvertTo-Json -Depth 5))
        if((Get-ResolverMutexName $root) -cne (Get-ResolverMutexName $root.ToUpperInvariant())){throw 'Self-test failed: mutex identity differs across case aliases'}
        Invoke-Resolver $root Resolve 20 $id $fixture|Out-Null;Invoke-Resolver $root VerifyCache 20 $id|Out-Null
        $workers=@(1,2|ForEach-Object{Start-Job -ScriptBlock {param($scriptPath,$testRoot,$testFixture)& $scriptPath -InternalSelfTestWorker -InternalSelfTestRoot $testRoot -InternalSelfTestFixture $testFixture} -ArgumentList $PSCommandPath,$root,$fixture})
        try { if(@(Wait-Job -Job $workers -Timeout 20).Count -ne 2){throw 'Self-test concurrent resolver workers timed out.'};foreach($worker in $workers){Receive-Job -Job $worker -ErrorAction Stop|Out-Null;if($worker.State -ne 'Completed'){throw 'Self-test concurrent resolver worker failed.'}} } finally {$workers|Remove-Job -Force -ErrorAction SilentlyContinue}
        Invoke-Resolver $root VerifyCache 20 $id|Out-Null
        $installHome=Join-Path $root 'local-artifacts\tools\gradle-9.4.1';[IO.File]::WriteAllText((Join-Path $installHome 'bin\gradle.bat'),'tamper')
        $failed=$false;try{Invoke-Resolver $root VerifyCache 20 $id|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: executable tamper'}
        Invoke-Resolver $root Resolve 20 $id $fixture|Out-Null;[IO.File]::WriteAllBytes((Join-Path $installHome 'lib\gradle-launcher-9.4.1.jar'),[byte[]](9,9,9))
        $failed=$false;try{Invoke-Resolver $root VerifyCache 20 $id|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: launcher JAR tamper'}
        Invoke-Resolver $root Resolve 20 $id $fixture|Out-Null;Remove-Item -LiteralPath (Join-Path $installHome 'bin\gradle.bat') -Force
        $failed=$false;try{Invoke-Resolver $root VerifyCache 20 $id|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: partial install'}
        Invoke-Resolver $root Resolve 20 $id $fixture|Out-Null;New-Item -ItemType Directory -Path (Join-Path $installHome 'init.d')|Out-Null;[IO.File]::WriteAllText((Join-Path $installHome 'init.d\evil.gradle'),'x')
        $failed=$false;try{Invoke-Resolver $root VerifyCache 20 $id|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: unexpected init script'}
        Invoke-Resolver $root Resolve 20 $id $fixture|Out-Null;[IO.File]::WriteAllText((Join-Path $root 'local-artifacts\downloads\gradle-9.4.1-bin.zip'),'archive-tamper')
        $failed=$false;try{Invoke-Resolver $root VerifyCache 20 $id|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: archive tamper with stale install'}
        Invoke-Resolver $root Resolve 20 $id $fixture|Out-Null;Remove-Item -LiteralPath (Join-Path $root 'local-artifacts\downloads\gradle-9.4.1-bin.zip') -Force
        $failed=$false;try{Invoke-Resolver $root VerifyCache 20 $id|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: offline absent archive'}
        $wrongVersion=$id.psobject.Copy();$wrongVersion.version='9.4.0';$failed=$false;try{Invoke-Resolver $root Resolve 20 $wrongVersion $fixture|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: wrong identity version'}
        $wrongSha=$id.psobject.Copy();$wrongSha.sha256=('0'*64);$failed=$false;try{Invoke-Resolver $root Resolve 20 $wrongSha $fixture|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: wrong identity SHA-256'}
        $wrongManifest=$id.psobject.Copy();$wrongManifest.max_path_depth=0;$failed=$false;try{Invoke-Resolver $root Resolve 20 $wrongManifest $fixture|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: malformed identity manifest'}
        $unsafeFixtures=@(
            @{label='reserved name';entries=@('gradle-9.4.1/CON.txt');symlink=$false},
            @{label='backslash traversal';entries=@('gradle-9.4.1\\..\\escape.txt');symlink=$false},
            @{label='ADS colon';entries=@('gradle-9.4.1/good.txt:ads');symlink=$false},
            @{label='dot traversal';entries=@('gradle-9.4.1/../escape.txt');symlink=$false},
            @{label='case collision';entries=@('gradle-9.4.1/Foo.txt','gradle-9.4.1/foo.TXT');symlink=$false},
            @{label='ZIP symlink';entries=@('gradle-9.4.1/link');symlink=$true}
        )
        foreach($fixtureSpec in $unsafeFixtures){
            $badArchive=Join-Path $root ("bad-$($fixtureSpec.label -replace '[^A-Za-z0-9]','-').zip");$before=@(Get-ChildItem -LiteralPath $root -Force -Recurse -File -Include '*.part','*.tmp').Count
            $badZip=[IO.Compression.ZipFile]::Open($badArchive,[IO.Compression.ZipArchiveMode]::Create);try{foreach($entryName in $fixtureSpec.entries){$entry=$badZip.CreateEntry([string]$entryName);if($fixtureSpec.symlink){$entry.ExternalAttributes=[BitConverter]::ToInt32([BitConverter]::GetBytes([Convert]::ToUInt32('A1FF0000',16)),0)}}}finally{$badZip.Dispose()}
            $failed=$false;try{Get-ArchiveTree $badArchive $id|Out-Null}catch{$failed=$true};if(-not $failed){throw "Self-test failed: unsafe Windows archive $($fixtureSpec.label)"};if(@(Get-ChildItem -LiteralPath $root -Force -Recurse -File -Include '*.part','*.tmp').Count -ne $before){throw "Self-test failed: unsafe archive temp cleanup $($fixtureSpec.label)"};Remove-Item -LiteralPath $badArchive -Force
        }
        $escape=Join-Path $root 'escape';New-Item -ItemType Directory -Path (Join-Path $escape 'config'),(Join-Path $root 'escape-target') -Force|Out-Null;[IO.File]::WriteAllText((Join-Path $escape 'config\gradle-9.4.1-tool.json'),($id|ConvertTo-Json -Depth 5));New-Item -ItemType Junction -Path (Join-Path $escape 'local-artifacts') -Target (Join-Path $root 'escape-target')|Out-Null;$failed=$false;try{Invoke-Resolver $escape VerifyCache 20 $id|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Self-test failed: reparse target escape'}
        $before=@(Get-ChildItem -LiteralPath (Join-Path $root 'local-artifacts\downloads') -Force -Filter '*.part').Count;$script:SelfTestObservedPart='';$failed=$false;try{Invoke-Resolver $root Resolve 20 $id '' -ProviderFailure|Out-Null}catch{$failed=$true};if(-not $failed -or [string]::IsNullOrWhiteSpace($script:SelfTestObservedPart) -or (Test-Path -LiteralPath $script:SelfTestObservedPart) -or @(Get-ChildItem -LiteralPath (Join-Path $root 'local-artifacts\downloads') -Force -Filter '*.part').Count -ne $before){throw 'Self-test provider failure/GUID temp cleanup failed'}
        Write-Host 'Gradle resolver self-test passed: good/concurrent cache, executable/JAR/archive/partial tamper, injected init rejection, wrong version/SHA/manifest, Windows ZIP/reparse rejection, offline failure, and real GUID temp cleanup.'
    } finally { if(Test-Path -LiteralPath $root){Remove-Item -LiteralPath $root -Recurse -Force} }
}

function Invoke-InternalSelfTestWorker {
    if([string]::IsNullOrWhiteSpace($InternalSelfTestRoot) -or [string]::IsNullOrWhiteSpace($InternalSelfTestFixture)){throw 'Internal self-test worker requires its generated fixture.'}
    $root=Get-FullPath $InternalSelfTestRoot;$temp=Get-FullPath ([IO.Path]::GetTempPath())
    if(-not $root.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or -not ([IO.Path]::GetFileName($root) -like 'rusty-quest-gradle-*') -or -not (Get-FullPath $InternalSelfTestFixture).StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Internal self-test worker refuses a non-temporary cache root.'}
    $id=Get-Content -LiteralPath (Join-Path $root 'config\gradle-9.4.1-tool.json') -Raw|ConvertFrom-Json;Invoke-Resolver $root Resolve 20 $id $InternalSelfTestFixture|Out-Null
}
if($InternalSelfTestWorker){Invoke-InternalSelfTestWorker;exit 0}
if($SelfTest){Invoke-SelfTest;exit 0}
$receipt=Invoke-Resolver $RepoRoot $Mode $TimeoutSeconds
$receipt|ConvertTo-Json -Depth 8
