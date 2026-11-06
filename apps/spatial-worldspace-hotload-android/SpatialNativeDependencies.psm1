Set-StrictMode -Version Latest

function Get-SpatialElfDependencies {
    [CmdletBinding()]
    param([Parameter(Mandatory)][byte[]]$Bytes)
    # Read program headers and PT_DYNAMIC, so stripped section tables are fine.
    function Range([long]$Offset,[long]$Size){
        if($Offset -lt 0 -or $Size -lt 0 -or $Offset -gt $Bytes.LongLength -or $Size -gt $Bytes.LongLength-$Offset){throw 'ELF range outside file.'}
    }
    function U16([long]$Offset){Range $Offset 2;[BitConverter]::ToUInt16($Bytes,[int]$Offset)}
    function U32([long]$Offset){Range $Offset 4;[BitConverter]::ToUInt32($Bytes,[int]$Offset)}
    function U64([long]$Offset){Range $Offset 8;[BitConverter]::ToUInt64($Bytes,[int]$Offset)}
    Range 0 64
    if($Bytes[0] -ne 127 -or [Text.Encoding]::ASCII.GetString($Bytes,1,3) -cne 'ELF' -or
       $Bytes[4] -ne 2 -or $Bytes[5] -ne 1 -or (U16 16) -ne 3 -or (U16 18) -ne 183){throw 'Expected little-endian ELF64 arm64 shared library.'}
    $phoff=U64 32;$stride=U16 54;$count=U16 56
    if($stride -ne 56 -or $count -eq 0 -or $count -eq 65535){throw 'Unsupported ELF program header table.'}
    Range $phoff ($stride*$count)
    $loads=@();$dynamic=@()
    for($i=0;$i -lt $count;$i++){
        $p=$phoff+$i*$stride;$type=U32 $p
        if($type -notin @(1,2)){continue}
        $offset=U64 ($p+8);$address=U64 ($p+16);$size=U64 ($p+32);$alignment=U64 ($p+48)
        Range $offset $size
        if($type -eq 1){
            if($alignment -lt 16384 -or ($alignment -band ($alignment-1)) -ne 0 -or
               ($offset%16384) -ne ($address%16384)){throw 'ELF LOAD lacks 16KiB alignment.'}
            $loads+=@{offset=$offset;address=$address;size=$size;alignment=$alignment}
        }else{$dynamic+=@{offset=$offset;size=$size}}
    }
    if($loads.Count -eq 0 -or $dynamic.Count -ne 1 -or $dynamic[0].size%16 -ne 0){throw 'ELF dynamic/load table missing or ambiguous.'}
    $needed=@();$strtab=@();$strsize=@();$ended=$false
    for($i=0;$i -lt $dynamic[0].size;$i+=16){
        $p=$dynamic[0].offset+$i;$tag=U64 $p;$value=U64 ($p+8)
        if($tag -eq 0){$ended=$true;break}
        if($tag -eq 1){$needed+=$value}
        if($tag -eq 5){$strtab+=$value}
        if($tag -eq 10){$strsize+=$value}
    }
    if(-not $ended -or $strtab.Count -ne 1 -or $strsize.Count -ne 1 -or $strsize[0] -eq 0){throw 'ELF string table missing or ambiguous.'}
    $mapped=@($loads|Where-Object {$strtab[0] -ge $_.address -and $strtab[0]-$_.address -le $_.size -and $strsize[0] -le $_.size-($strtab[0]-$_.address)})
    if($mapped.Count -ne 1){throw 'ELF string table is not uniquely file-backed.'}
    $start=$mapped[0].offset+($strtab[0]-$mapped[0].address)
    $names=@()
    foreach($index in $needed){
        if($index -ge $strsize[0]){throw 'ELF dependency string outside table.'}
        $end=$index
        while($end -lt $strsize[0] -and $Bytes[$start+$end] -ne 0){$end++}
        if($end -eq $strsize[0]){throw 'Unterminated ELF dependency string.'}
        $name=[Text.Encoding]::ASCII.GetString($Bytes,[int]($start+$index),[int]($end-$index))
        if($name -cnotmatch '^[A-Za-z0-9_+.-]+\.so(?:\.[0-9]+)*$'){throw 'Unsupported ELF dependency name.'}
        $names+=$name
    }
    [pscustomobject]@{needed=@($names);load_alignment=@($loads|ForEach-Object {$_.alignment})}
}

function Read-SpatialApkNativeLibraries {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Apk)
    $libraries=[Collections.Generic.Dictionary[string,byte[]]]::new([StringComparer]::Ordinal)
    $zip=[IO.Compression.ZipFile]::OpenRead($Apk)
    try{
        foreach($entry in $zip.Entries){
            if($entry.FullName -cnotmatch '^lib/arm64-v8a/([^/]+\.so)$'){continue}
            $name=$Matches[1]
            if($libraries.ContainsKey($name)){throw "Duplicate APK native library: $name"}
            $stream=$entry.Open();$memory=[IO.MemoryStream]::new()
            try{$stream.CopyTo($memory);$libraries.Add($name,$memory.ToArray())}finally{$stream.Dispose();$memory.Dispose()}
        }
    }finally{$zip.Dispose()}
    if($libraries.Count -eq 0){throw 'APK arm64 native libraries absent.'}
    return ,$libraries
}

function Assert-SpatialNativeDependencyClosure {
    [CmdletBinding()]
    param([Parameter(Mandatory)][Collections.IDictionary]$Libraries,
          [Parameter(Mandatory)][string[]]$SystemLibraryNames)
    $system=[Collections.Generic.HashSet[string]]::new([string[]]$SystemLibraryNames,[StringComparer]::Ordinal)
    # Never accept the application C++ runtime as an Android system library.
    if($system.Contains('libc++_shared.so')){throw 'Shared C++ runtime must be packaged, not system-exempted.'}
    $result=@()
    foreach($name in @($Libraries.Keys|Sort-Object -CaseSensitive)){
        $elf=Get-SpatialElfDependencies -Bytes $Libraries[$name]
        foreach($needed in $elf.needed){
            if($Libraries.Keys -cnotcontains $needed -and -not $system.Contains($needed)){throw "Missing packaged native dependency: $name -> $needed"}
        }
        $result+=[ordered]@{name=$name;needed=$elf.needed;load_alignment=$elf.load_alignment}
    }
    return $result
}

Export-ModuleMember -Function Get-SpatialElfDependencies,Read-SpatialApkNativeLibraries,Assert-SpatialNativeDependencyClosure
