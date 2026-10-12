param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'),
      [string]$ActualJniInputPath, [string]$ActualApkPath)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$builder=Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Actual metadata producer parse failed'}
foreach($name in @('Get-FileSha256','Get-ZipEntrySha256','Get-NativeReceiptArtifactMetadata')){
    $nodes=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-ceq$name},$false))
    if($nodes.Count-ne1){throw "Metadata producer function not unique: $name"}
    Invoke-Expression $nodes[0].Extent.Text
}
$source=Get-Content -LiteralPath $builder -Raw
foreach($required in @('native_receipt_library_sha256 = $nativeReceiptSha256',
    'native_receipt_library_sha256_scope = "generated-jni-input"',
    'native_receipt_artifacts = $nativeReceiptArtifacts',
    '$nativeReceiptArtifacts = Get-NativeReceiptArtifactMetadata',
    '$nativeReceiptArtifacts.input.sha256 -cne $nativeReceiptSha256')){
    if(-not$source.Contains($required)){throw "Actual producer role/join missing: $required"}
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('rq-native-metadata-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch|Out-Null
$entry='lib/arm64-v8a/libspatial_camera_panel_native_receipt.so'
function NewFixtureZip($path,$names,$bytes){
    if($null-eq$bytes){$bytes=[byte[]]@()}
    $zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try{foreach($name in $names){$output=$zip.CreateEntry($name).Open();try{$output.Write($bytes,0,$bytes.Length)}finally{$output.Dispose()}}}finally{$zip.Dispose()}
}
function Metadata($inputPath,$apkPath){
    $zip=[IO.Compression.ZipFile]::OpenRead($apkPath)
    try{return Get-NativeReceiptArtifactMetadata -JniInputPath $inputPath -Zip $zip -ApkEntryName $entry}finally{$zip.Dispose()}
}
try{
    $input=Join-Path $scratch 'input.so';$inputBytes=[Text.Encoding]::UTF8.GetBytes('native ELF input with symbols')
    [IO.File]::WriteAllBytes($input,$inputBytes)
    $packedBytes=[Text.Encoding]::UTF8.GetBytes('native ELF stripped');$apk=Join-Path $scratch 'good.zip'
    NewFixtureZip $apk @($entry) $packedBytes
    $record=Metadata $input $apk
    $actualPacked=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($packedBytes)).ToLowerInvariant()
    if($record.input.sha256-cne(Get-FileSha256 $input)-or$record.packaged.sha256-cne$actualPacked-or$record.input.sha256-ceq$record.packaged.sha256){throw 'Input/packaged roles collapsed'}
    if($record.input.role-cne'generated-jni-input'-or$record.packaged.role-cne'apk-zip-entry'-or$record.packaged.path-cne$entry-or$record.input.size_bytes-ne$inputBytes.Length-or$record.packaged.size_bytes-ne$packedBytes.Length){throw 'Typed path/byte/hash role differs'}
    $same=Join-Path $scratch 'same.zip';NewFixtureZip $same @($entry) $inputBytes
    if((Metadata $input $same).packaged.sha256-cne$record.input.sha256){throw 'Unstripped valid packaging rejected'}
    foreach($fixture in @(@{name='missing';entries=@('other.so')},@{name='duplicate';entries=@($entry,$entry)},@{name='empty';entries=@($entry);empty=$true})){
        $path=Join-Path $scratch ($fixture.name+'.zip');$bytes=if($fixture.ContainsKey('empty')){[byte[]]@()}else{$packedBytes};NewFixtureZip $path $fixture.entries $bytes
        $denied=$false;try{$null=Metadata $input $path}catch{$denied=$true};if(-not$denied){throw "Malformed packaged entry accepted: $($fixture.name)"}
    }
    $emptyInput=Join-Path $scratch 'empty.so';[IO.File]::WriteAllBytes($emptyInput,[byte[]]@())
    foreach($bad in @($emptyInput,$scratch,(Join-Path $scratch 'absent.so'))){$denied=$false;try{$null=Metadata $bad $apk}catch{$denied=$true};if(-not$denied){throw 'Invalid input file accepted'}}
    if([bool]$ActualJniInputPath-ne[bool]$ActualApkPath){throw 'Actual input and APK must be supplied together'}
    if($ActualJniInputPath){
        $observed=Metadata $ActualJniInputPath $ActualApkPath
        $observed|ConvertTo-Json -Depth 10
    }
    Write-Output 'Native receipt artifact metadata controls passed: distinct/equal roles, missing/duplicate/empty ZIP, empty/directory/missing input; compiler/device calls=0'
}finally{
    $resolved=(Resolve-Path -LiteralPath $scratch).Path
    $expected=[IO.Path]::GetFullPath($scratch)
    $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if($resolved-cne$expected-or-not$resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase)-or[IO.Path]::GetFileName($resolved)-cnotmatch'^rq-native-metadata-[0-9a-f]{32}$'){throw 'Fixture cleanup target changed'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
