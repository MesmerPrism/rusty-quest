Set-StrictMode -Version Latest
function Assert-AndroidNativeSourcePaths {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$SourceDirectory,[Parameter(Mandatory)][string]$ObjectDirectory)
    $files=@(Get-ChildItem -LiteralPath $SourceDirectory -Recurse -File -ErrorAction Stop |
        Where-Object Extension -In @('.c','.cc','.cpp','.cxx','.h','.hpp'))
    if($files.Count -eq 0){throw 'Selected Android CMake source directory is empty.'}
    $maximum=0;$objectMaximum=0
    foreach($file in $files){
        $length=[IO.Path]::GetFullPath($file.FullName).Length
        $maximum=[Math]::Max($maximum,$length)
        if($length -gt 260){throw 'Selected Android CMake source path exceeds Ninja 260-character source filename limit; select a shorter source root before compiling.'}
        if($file.Extension -in @('.c','.cc','.cpp','.cxx')){
            $objectLength=(Join-Path $ObjectDirectory ($file.Name+'.o.d')).Length
            $objectMaximum=[Math]::Max($objectMaximum,$objectLength)
            if($objectLength -ge 250){throw 'Selected Android CMake source-derived object path exceeds CMake 250-character limit; select a shorter cache root before compiling.'}
        }
    }
    return [ordered]@{status='passed';source_count=$files.Count;maximum_source_path_length=$maximum;source_path_limit=260;
        projected_object_path_length=$objectMaximum;object_path_limit=250}
}
Export-ModuleMember -Function Assert-AndroidNativeSourcePaths
