#Requires -Version 7.6
# Optional per-app debug API layer. Inputs remain external; no binaries or host setup live here.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script:LayerName = 'XR_APILAYER_METAX_operator'
$script:LibraryName = 'libXrApiLayer_METAX_operator.so'
$script:ManifestName = 'xr_api_layer_metax_operator.json'
$script:ManifestEntry = 'assets/openxr/1/api_layers/implicit.d/xr_api_layer_metax_operator.json'
$script:LibraryEntry = 'lib/arm64-v8a/libXrApiLayer_METAX_operator.so'

function Read-OperatorBytes([string]$Path, [string]$ExpectedSha256, [int]$Maximum) {
    if ($ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$' -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Operator input requires an existing file and exact lowercase SHA256.' }
    $info = Get-Item -LiteralPath $Path
    if ($info.Length -le 0 -or $info.Length -gt $Maximum) { throw 'Operator input exceeds its closed byte bound.' }
    $bytes = [IO.File]::ReadAllBytes($info.FullName)
    if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $ExpectedSha256) { throw 'Operator input SHA256 mismatch.' }
    return ,$bytes
}

function Assert-OperatorJsonKeys($Element, [string[]]$Keys) {
    if ($Element.ValueKind -ne [Text.Json.JsonValueKind]::Object) { throw 'Operator manifest object required.' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($property in $Element.EnumerateObject()) {
        if (-not $seen.Add($property.Name) -or $Keys -cnotcontains $property.Name) { throw 'Operator manifest duplicate or unknown field.' }
    }
    if ($seen.Count -ne $Keys.Count) { throw 'Operator manifest field missing.' }
}

function Assert-OperatorManifest([byte[]]$Bytes) {
    $text = [Text.UTF8Encoding]::new($false, $true).GetString($Bytes)
    $document = [Text.Json.JsonDocument]::Parse($text)
    try {
        $root = $document.RootElement
        Assert-OperatorJsonKeys $root @('file_format_version','api_layer')
        if ($root.GetProperty('file_format_version').GetString() -cne '1.0.0') { throw 'Operator manifest format unsupported.' }
        $layer = $root.GetProperty('api_layer')
        Assert-OperatorJsonKeys $layer @('name','library_path','api_version','implementation_version','description','disable_environment','instance_extensions')
        foreach ($pair in @(@('name',$script:LayerName),@('library_path',('./'+$script:LibraryName)),@('api_version','1.1'),@('implementation_version','1'),@('disable_environment','METAX_operator_disabled'))) {
            if ($layer.GetProperty($pair[0]).GetString() -cne $pair[1]) { throw "Operator manifest $($pair[0]) differs from the closed Android layer." }
        }
        $description = $layer.GetProperty('description').GetString()
        if ([string]::IsNullOrWhiteSpace($description) -or $description.Length -gt 256) { throw 'Operator description invalid.' }
        $extensions = $layer.GetProperty('instance_extensions')
        if ($extensions.ValueKind -ne [Text.Json.JsonValueKind]::Array -or $extensions.GetArrayLength() -ne 1) { throw 'Operator extension closure differs.' }
        $extension = $extensions[0]
        Assert-OperatorJsonKeys $extension @('name','extension_version')
        if ($extension.GetProperty('name').GetString() -cne 'XR_METAX1_agentic_external_tool' -or $extension.GetProperty('extension_version').GetString() -cne '1') { throw 'Operator extension identity differs.' }
    } finally { $document.Dispose() }
}

function Get-OperatorElf([byte[]]$Bytes) {
    function Span([ulong]$Offset, [ulong]$Length) {
        if ($Offset -gt [ulong]$Bytes.Length -or $Length -gt ([ulong]$Bytes.Length - $Offset)) { throw 'Operator ELF range exceeds file.' }
    }
    function U16([ulong]$Offset) { Span $Offset 2; [BitConverter]::ToUInt16($Bytes, [int]$Offset) }
    function U32([ulong]$Offset) { Span $Offset 4; [BitConverter]::ToUInt32($Bytes, [int]$Offset) }
    function U64([ulong]$Offset) { Span $Offset 8; [BitConverter]::ToUInt64($Bytes, [int]$Offset) }
    Span 0 64
    if ($Bytes[0] -ne 127 -or [Text.Encoding]::ASCII.GetString($Bytes,1,3) -cne 'ELF' -or $Bytes[4] -ne 2 -or $Bytes[5] -ne 1 -or $Bytes[6] -ne 1 -or (U16 16) -ne 3 -or (U16 18) -ne 183 -or (U32 20) -ne 1 -or (U16 52) -ne 64 -or (U16 54) -ne 56) { throw 'Operator requires a little-endian ELF64 AArch64 shared library.' }
    $count = U16 56; $table = U64 32
    if ($count -lt 1 -or $count -gt 128) { throw 'Operator ELF program-header count invalid.' }
    Span $table ([ulong]$count * 56)
    $loads = @(); $dynamic = @()
    for ($index=0; $index -lt $count; $index++) {
        $offset=$table+[ulong]($index*56); $type=U32 $offset; $flags=U32 ($offset+4)
        $segment=[pscustomobject]@{offset=(U64 ($offset+8));address=(U64 ($offset+16));length=(U64 ($offset+32));memory=(U64 ($offset+40));alignment=(U64 ($offset+48));flags=$flags}
        Span $segment.offset $segment.length
        if ($type -eq 1) {
            $align=$segment.alignment
            if ($segment.memory -lt $segment.length -or $align -lt 4096 -or $align -gt 1073741824 -or ($align -band ($align-1)) -ne 0 -or ($segment.offset % $align) -ne ($segment.address % $align)) { throw 'Operator ELF LOAD alignment/range invalid.' }
            $loads += $segment
        } elseif ($type -eq 2) { $dynamic += $segment }
        elseif ($type -eq 0x6474e551 -and ($flags -band 1)) { throw 'Operator executable stack rejected.' }
    }
    if ($loads.Count -lt 1 -or @($loads | Where-Object { $_.flags -band 1 }).Count -eq 0 -or $dynamic.Count -ne 1 -or $dynamic[0].length -eq 0 -or $dynamic[0].length % 16 -ne 0 -or $dynamic[0].length -gt 65536) { throw 'Operator ELF load/dynamic closure invalid.' }
    $strings=$null; $stringBytes=$null; $soname=$null; $needed=@(); $ended=$false
    for ([ulong]$offset=$dynamic[0].offset; $offset -lt $dynamic[0].offset+$dynamic[0].length; $offset+=16) {
        $tag=U64 $offset; $value=U64 ($offset+8)
        if ($tag -eq 0) { $ended=$true; break }
        if ($tag -eq 1) { $needed += $value }
        elseif ($tag -eq 5) { if ($null -ne $strings) { throw 'Duplicate Operator string table.' }; $strings=$value }
        elseif ($tag -eq 10) { if ($null -ne $stringBytes) { throw 'Duplicate Operator string size.' }; $stringBytes=$value }
        elseif ($tag -eq 14) { if ($null -ne $soname) { throw 'Duplicate Operator SONAME.' }; $soname=$value }
    }
    if (-not $ended -or $null -eq $strings -or $null -eq $stringBytes -or $null -eq $soname -or $stringBytes -eq 0 -or $stringBytes -gt 1048576) { throw 'Operator dynamic string closure missing.' }
    $mapping=@($loads | Where-Object { $strings -ge $_.address -and ($strings-$_.address) -lt $_.length -and $stringBytes -le ($_.length-($strings-$_.address)) })
    if ($mapping.Count -ne 1) { throw 'Operator string table has no unique file-backed LOAD.' }
    $base=$mapping[0].offset+($strings-$mapping[0].address)
    function ElfString([ulong]$Index) {
        if ($Index -ge $stringBytes) { throw 'Operator ELF string index invalid.' }
        $start=$base+$Index; $end=$start
        while ($end -lt $base+$stringBytes -and $Bytes[[int]$end] -ne 0) { $end++ }
        if ($end -eq $base+$stringBytes -or $end -eq $start -or $end-$start -gt 128) { throw 'Operator ELF string unterminated or unbounded.' }
        $value=[Text.UTF8Encoding]::new($false,$true).GetString($Bytes,[int]$start,[int]($end-$start))
        if ($value -cnotmatch '^lib[A-Za-z0-9_+.\-]+\.so$') { throw 'Operator ELF dependency path rejected.' }
        $value
    }
    if ((ElfString $soname) -cne $script:LibraryName) { throw 'Operator SONAME differs.' }
    $dependencies=@($needed | ForEach-Object { ElfString $_ } | Sort-Object)
    $expected=@('libandroid.so','libc.so','libdl.so','liblog.so','libm.so','libmediandk.so')
    if (($dependencies -join '|') -cne ($expected -join '|')) { throw 'Operator platform dependency closure differs.' }
    [pscustomobject]@{class='ELF64';machine='AArch64';type='DYN';soname=$script:LibraryName;dependencies=$dependencies;load_alignments_bytes=@($loads.alignment | Sort-Object -Unique);api_version='1.1'}
}

function Get-QuestMetaXrOperatorSelection {
    [CmdletBinding()]
    param([string]$FeatureLockPath='', [string]$FeatureLockSha256='',
        [string]$AndroidManifestPath='', [string]$PackageName='',
        [ValidateSet('NativeOpenXr','SpatialSdk')][string]$Consumer='NativeOpenXr',
        [switch]$Enable, [string]$LibraryPath='', [string]$LibrarySha256='',
        [string]$LayerManifestPath='', [string]$LayerManifestSha256='')
    $inputs=@($FeatureLockPath,$FeatureLockSha256,$AndroidManifestPath,$PackageName,$LibraryPath,$LibrarySha256,$LayerManifestPath,$LayerManifestSha256)
    if (-not $Enable -and @($inputs | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -eq 0) {
        return [pscustomobject]@{enabled=$false;fingerprint='disabled';runtime_verified=$false}
    }
    if (-not $Enable) { throw 'Operator inputs require explicit debug opt-in.' }
    $lockBytes=Read-OperatorBytes $FeatureLockPath $FeatureLockSha256 1048576
    $document=[Text.Json.JsonDocument]::Parse([Text.UTF8Encoding]::new($false,$true).GetString($lockBytes))
    try {
        $root=$document.RootElement
        if ($root.ValueKind -ne [Text.Json.JsonValueKind]::Object) { throw 'Resolved app feature lock required.' }
        $keys=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($property in $root.EnumerateObject()) { if(-not $keys.Add($property.Name)){throw 'Duplicate app lock field.'} }
        if($root.GetProperty('schema').GetString() -cne 'rusty.quest.native_app_feature_lock.v1'){throw 'Use the existing resolved native app feature lock, not a new authority format.'}
        $features=$root.GetProperty('selected_feature_ids')
        if($features.ValueKind -ne [Text.Json.JsonValueKind]::Array){throw 'Selected feature array required.'}
        $ids=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($id in $features.EnumerateArray()){if(-not $ids.Add($id.GetString())){throw 'Duplicate selected feature.'}}
        if(-not $ids.Contains('debug.meta_xr_operator')){throw 'Operator source feature is not selected.'}
    } finally { $document.Dispose() }
    if($PackageName -cnotmatch '^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$'){throw 'Exact Android package required.'}
    $androidBytes=[IO.File]::ReadAllBytes($AndroidManifestPath)
    if($androidBytes.Length -gt 1048576){throw 'Android manifest exceeds bound.'}
    $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
    $stream=[IO.MemoryStream]::new($androidBytes,$false);$reader=[Xml.XmlReader]::Create($stream,$settings)
    try{$xml=[Xml.XmlDocument]::new();$xml.XmlResolver=$null;$xml.Load($reader)}finally{$reader.Dispose();$stream.Dispose()}
    $ns=[Xml.XmlNamespaceManager]::new($xml.NameTable);$ns.AddNamespace('a','http://schemas.android.com/apk/res/android')
    $apps=@($xml.SelectNodes('//*[local-name()="application"]'))
    if($xml.DocumentElement.Name -cne 'manifest' -or $xml.DocumentElement.NamespaceURI -cne '' -or
        $xml.DocumentElement.GetAttribute('package') -cne $PackageName -or $apps.Count -ne 1 -or
        $apps[0].ParentNode -ne $xml.DocumentElement -or $apps[0].NamespaceURI -cne '' -or
        $apps[0].GetAttribute('debuggable','http://schemas.android.com/apk/res/android') -cne 'true' -or
        $xml.SelectNodes('/manifest/uses-permission[@a:name="android.permission.INTERNET"]',$ns).Count -ne 1 -or
        $xml.SelectNodes('/manifest/uses-feature[@a:name="com.oculus.experimental.enabled"]',$ns).Count -ne 1 -or
        $xml.SelectNodes('/manifest/uses-feature[@a:name="com.oculus.experimental.enabled" and @a:required="false"]',$ns).Count -ne 1){throw 'Exact debuggable app, Internet and optional experimental declaration required.'}
    if($Consumer -ceq 'SpatialSdk') { Assert-QuestMetaXrOperatorMergedManifest -Enabled $true -Path $AndroidManifestPath }
    $library=Read-OperatorBytes $LibraryPath $LibrarySha256 67108864
    $manifest=Read-OperatorBytes $LayerManifestPath $LayerManifestSha256 32768
    Assert-OperatorManifest $manifest
    $elf=Get-OperatorElf $library
    if($Consumer -ceq 'SpatialSdk' -and @($elf.load_alignments_bytes | Where-Object {$_ -lt 16384}).Count){throw 'Selected Spatial SDK layer requires 16KiB LOAD alignment.'}
    $androidHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($androidBytes)).ToLowerInvariant()
    $fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes("$Consumer|$PackageName|$FeatureLockSha256|$androidHash|$([IO.Path]::GetFullPath($LibraryPath))|$([IO.Path]::GetFullPath($LayerManifestPath))|$LibrarySha256|$LayerManifestSha256"))).ToLowerInvariant()
    [pscustomobject]@{enabled=$true;fingerprint=$fingerprint;feature_id='debug.meta_xr_operator';package=$PackageName;consumer=$Consumer;feature_lock_sha256=$FeatureLockSha256;android_manifest_sha256=$androidHash;layer_name=$script:LayerName;library=[pscustomobject]@{source_path=[IO.Path]::GetFullPath($LibraryPath);sha256=$LibrarySha256;bytes=$library.Length;apk_path=$script:LibraryEntry};layer_manifest=[pscustomobject]@{source_path=[IO.Path]::GetFullPath($LayerManifestPath);sha256=$LayerManifestSha256;bytes=$manifest.Length;apk_path=$script:ManifestEntry};elf=$elf;runtime_verified=$false;optional_capture_selected=$false}
}

function Assert-OperatorSelection($Selection) {
    if($Selection.enabled -isnot [bool]){throw 'Boolean selection required.'}
    if(-not $Selection.enabled){return}
    if($Selection.feature_id -cne 'debug.meta_xr_operator' -or $Selection.layer_name -cne $script:LayerName -or
        $Selection.library.apk_path -cne $script:LibraryEntry -or $Selection.layer_manifest.apk_path -cne $script:ManifestEntry -or
        $Selection.consumer -cnotin @('NativeOpenXr','SpatialSdk') -or
        $Selection.package -cnotmatch '^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$'){throw 'Operator selection identity/path differs.'}
    $parts=@($Selection.feature_lock_sha256,$Selection.android_manifest_sha256,$Selection.library.sha256,$Selection.layer_manifest.sha256)
    foreach($hash in $parts){if($hash -cnotmatch '^[0-9a-f]{64}$'){throw 'Selection hash invalid.'}}
    $text="$($Selection.consumer)|$($Selection.package)|$($parts[0])|$($parts[1])|$($Selection.library.source_path)|$($Selection.layer_manifest.source_path)|$($parts[2])|$($parts[3])"
    $actual=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()
    if($actual -cne $Selection.fingerprint){throw 'Operator selection fingerprint changed.'}
}

function Copy-QuestMetaXrOperatorLayer {
    param([Parameter(Mandatory)]$Selection,[Parameter(Mandatory)][string]$OutputRoot,[Parameter(Mandatory)][string]$NativeStageRoot)
    Assert-OperatorSelection $Selection
    if (-not $Selection.enabled) { return }
    if(Test-Path -LiteralPath $OutputRoot){throw 'Fresh Operator staging root required.'}
    if(Test-Path -LiteralPath $NativeStageRoot){throw 'Fresh native staging root required.'}
    # Validate and snapshot BOTH inputs before any destination mutation. A late
    # second-input failure must not leave a misleading half-selected package.
    $prepared=[Collections.Generic.List[object]]::new()
    foreach ($record in @($Selection.library,$Selection.layer_manifest)) {
        $limit=if ($record.apk_path.StartsWith('lib/')) { 67108864 } else { 32768 }
        $bytes=Read-OperatorBytes $record.source_path $record.sha256 $limit
        if($bytes.Length -ne $record.bytes){throw 'Selected artifact byte count changed.'}
        $root=if ($record.apk_path.StartsWith('lib/')) { $NativeStageRoot } else { $OutputRoot }
        $prepared.Add([pscustomobject]@{destination=(Join-Path $root $record.apk_path);bytes=$bytes})
    }
    foreach($item in $prepared){
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($item.destination))
        $stream=[IO.File]::Open($item.destination,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($item.bytes); $stream.Flush($true) } finally { $stream.Dispose() }
    }
}

function Assert-QuestMetaXrOperatorArchive {
    param([Parameter(Mandatory)]$Selection,[Parameter(Mandatory)][string]$ApkPath)
    Assert-OperatorSelection $Selection
    $archive=[IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $entries=@($archive.Entries | Where-Object { $_.FullName -match '(?i)metax_operator|meta.xr.operator' })
        if (-not $Selection.enabled) { if ($entries.Count -ne 0) { throw 'Unselected Operator artifact in APK.' }; return }
        if ($entries.Count -ne 2) { throw 'Operator APK entry closure differs.' }
        foreach ($record in @($Selection.library,$Selection.layer_manifest)) {
            $matchingEntries=@($entries | Where-Object { $_.FullName -ceq $record.apk_path })
            if ($matchingEntries.Count -ne 1 -or $matchingEntries[0].Length -ne $record.bytes) { throw 'Operator APK path/length mismatch.' }
            $stream=$matchingEntries[0].Open()
            try { $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() } finally { $stream.Dispose() }
            if ($hash -cne $record.sha256) { throw 'Operator APK bytes mismatch.' }
        }
    } finally { $archive.Dispose() }
}
function Assert-QuestMetaXrOperatorManifestDump {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Selection,[Parameter(Mandatory)][string[]]$Lines,[Parameter(Mandatory)][int]$ExitCode,
        [switch]$RequireNativeExtraction)
    Assert-OperatorSelection $Selection
    if(-not $Selection.enabled){throw 'Packaged inspection requires a selected debug closure.'}
    if ($ExitCode -ne 0) { throw 'Packaged Operator manifest inspection failed.' }
    $elements = [Collections.Generic.List[object]]::new()
    $stack = [Collections.Generic.List[object]]::new()
    $attributes = [Collections.Generic.List[object]]::new()
    $packages = [Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        $element = [regex]::Match($line, '^( *)E: ([^ (]+)(?:\s|$)')
        if ($element.Success) {
            $indent = $element.Groups[1].Length
            while ($stack.Count -and $stack[$stack.Count-1].indent -ge $indent) { $stack.RemoveAt($stack.Count-1) }
            $node = [pscustomobject]@{id=$elements.Count;name=$element.Groups[2].Value;indent=$indent;parent=$(if($stack.Count){$stack[$stack.Count-1].id}else{-1})}
            $elements.Add($node);$stack.Add($node)
            continue
        }
        $package = [regex]::Match($line, '^( *)A: package="([^"]+)"(?: \(Raw: "\2"\))?\s*$')
        if($package.Success){$packages.Add([pscustomobject]@{owner=$(if($stack.Count){$stack[$stack.Count-1].id}else{-1});indent=$package.Groups[1].Length;value=$package.Groups[2].Value})}
        $attribute = [regex]::Match($line, '^( *)A: ((?:http://schemas\.android\.com/apk/res/android:|android:)(debuggable|extractNativeLibs)(?:\(|=|\s).*)$')
        if ($attribute.Success) {
            $attributes.Add([pscustomobject]@{owner=$(if($stack.Count){$stack[$stack.Count-1].id}else{-1});indent=$attribute.Groups[1].Length;text=$attribute.Groups[2].Value;name=$attribute.Groups[3].Value})
        }
    }
    $manifests = @($elements | Where-Object { $_.name -ceq 'manifest' })
    $applications = @($elements | Where-Object { $_.name -ceq 'application' })
    $debugAttributes=@($attributes|Where-Object name -CEQ 'debuggable')
    if($manifests.Count -ne 1 -or $packages.Count -ne 1 -or $packages[0].owner -ne $manifests[0].id -or
        $packages[0].indent -ne $manifests[0].indent+2 -or $packages[0].value -cne $Selection.package){throw 'Packaged Android package does not match selected app.'}
    if ($manifests.Count -ne 1 -or $manifests[0].parent -ne -1 -or $applications.Count -ne 1 -or
        $applications[0].parent -ne $manifests[0].id -or
        $debugAttributes.Count -ne 1 -or $debugAttributes[0].owner -ne $applications[0].id -or
        $debugAttributes[0].indent -ne $applications[0].indent+2 -or
        -not [regex]::IsMatch($debugAttributes[0].text, '^(?:http://schemas\.android\.com/apk/res/android:|android:)debuggable\(0x0101000f\)=(?:true|\(type 0x12\)0xffffffff)\s*$')) {
        throw 'Packaged Operator app is not explicitly debuggable.'
    }
    if($RequireNativeExtraction -or $Selection.consumer -ceq 'SpatialSdk'){
        $extraction=@($attributes|Where-Object name -CEQ 'extractNativeLibs')
        if($extraction.Count -ne 1 -or $extraction[0].owner -ne $applications[0].id -or
            $extraction[0].indent -ne $applications[0].indent+2 -or
            -not [regex]::IsMatch($extraction[0].text,'^(?:http://schemas\.android\.com/apk/res/android:|android:)extractNativeLibs\(0x010104ea\)=(?:true|\(type 0x12\)0xffffffff)\s*$')){
            throw 'Packaged physical-path Operator requires application extractNativeLibs=true.'
        }
    }
}
function Assert-QuestMetaXrOperatorMergedManifest {
    [CmdletBinding()]
    param([Parameter(Mandatory)][bool]$Enabled,[Parameter(Mandatory)][string]$Path)
    if(-not $Enabled){return} # No extraction assertion for ordinary/unselected packaging.
    $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
    $reader=[Xml.XmlReader]::Create($Path,$settings)
    try{$document=[Xml.XmlDocument]::new();$document.XmlResolver=$null;$document.Load($reader)}finally{$reader.Dispose()}
    $root=$document.DocumentElement
    $applications=@($document.SelectNodes('//*[local-name()="application"]'))
    if($root.Name -cne 'manifest' -or $root.NamespaceURI -cne '' -or $applications.Count -ne 1 -or
        $applications[0].ParentNode -ne $root -or $applications[0].NamespaceURI -cne ''){throw 'Merged Operator requires one manifest-owned application.'}
    $extraction=@($document.SelectNodes('//@*[local-name()="extractNativeLibs"]'))
    if($extraction.Count -ne 1 -or $extraction[0].OwnerElement -ne $applications[0] -or
        $extraction[0].NamespaceURI -cne 'http://schemas.android.com/apk/res/android' -or
        $extraction[0].Value -cne 'true'){throw 'Merged physical-path Operator requires application extractNativeLibs=true.'}
}
Export-ModuleMember -Function Get-QuestMetaXrOperatorSelection,Copy-QuestMetaXrOperatorLayer,Assert-QuestMetaXrOperatorArchive,Assert-QuestMetaXrOperatorManifestDump,Assert-QuestMetaXrOperatorMergedManifest
