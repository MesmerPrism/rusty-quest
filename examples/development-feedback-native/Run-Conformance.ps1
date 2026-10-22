[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Rustc,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.local')
)
$ErrorActionPreference = 'Stop'
$whole = [Diagnostics.Stopwatch]::StartNew()
$compiler = (Resolve-Path -LiteralPath $Rustc).Path
if ([IO.Path]::GetFileNameWithoutExtension($compiler) -ne 'rustc') { throw 'Select an explicit rustc executable' }
$run = Join-Path $OutputRoot ('run-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$run = (Resolve-Path -LiteralPath $run).Path
$timings = [Collections.Generic.List[object]]::new()
$passed = $false
function Invoke-Compiler([string]$Name, [string[]]$CompilerArguments) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    & $compiler @CompilerArguments 2>&1 | Tee-Object -FilePath (Join-Path $run ($Name + '-compile.txt'))
    if ($LASTEXITCODE -ne 0) { throw "Compiler failed: $Name ($LASTEXITCODE)" }
    $timings.Add([ordered]@{ name = $Name; elapsed_seconds = $clock.Elapsed.TotalSeconds })
}
try {
    & $compiler -vV | Set-Content (Join-Path $run 'rustc.txt')
    if ($LASTEXITCODE -ne 0) { throw 'rustc identity failed' }
    $compilerHash = (Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant()
    $suffix = if ($IsWindows) { '.dll' } elseif ($IsMacOS) { '.dylib' } else { '.so' }
    $exe = if ($IsWindows) { '.exe' } else { '' }
    $source = Join-Path $PSScriptRoot 'src'
    $sourceHashes = @(Get-ChildItem -LiteralPath $source -File | Sort-Object Name | ForEach-Object {
        [ordered]@{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    Invoke-Compiler 'unit-tests' @('--edition','2021','--test',(Join-Path $source 'main.rs'),'-D','warnings','-o',(Join-Path $run ('unit-tests' + $exe)))
    & (Join-Path $run ('unit-tests' + $exe)) 2>&1 | Tee-Object (Join-Path $run 'unit-tests.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Unit tests failed' }
    Invoke-Compiler 'host' @('--edition','2021',(Join-Path $source 'main.rs'),'-D','warnings','-C','panic=abort','-o',(Join-Path $run ('feedback-host' + $exe)))
    $manifest = [Collections.Generic.List[string]]::new()
    $artifacts = [Collections.Generic.List[object]]::new()
    foreach ($definition in @(@('a1',1,0), @('b2',2,0), @('a3',3,0), @('wrong-version',4,1), @('short-table',5,2), @('wrong-contract',6,3), @('bad-behavior',7,4))) {
        $name, $generation, $variant = $definition
        $wrapper = Join-Path $run ($name + '.rs')
        $abiPath = (Join-Path $source 'abi.rs') -replace '\\','/'
        $pluginPath = (Join-Path $source 'plugin.rs') -replace '\\','/'
        @"
#![allow(dead_code)]
const GENERATION: u64 = $generation;
const VARIANT: u32 = $variant;
#[path = "$abiPath"] mod abi;
include!("$pluginPath");
"@ | Set-Content -LiteralPath $wrapper -Encoding utf8NoBOM
        $artifact = Join-Path $run ($name + $suffix)
        $arguments = @('--edition','2021','--crate-type','cdylib','--crate-name',($name -replace '-','_'),$wrapper,'-C','panic=abort','-D','warnings','-o',$artifact)
        if ($name -eq 'b2') { $arguments += @('--cfg','phase_b') }
        Invoke-Compiler $name $arguments
        $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        $manifest.Add("$name`t$generation`t$hash`t$artifact")
        $artifacts.Add([ordered]@{ name = $name; generation = $generation; sha256 = $hash; bytes = (Get-Item -LiteralPath $artifact).Length })
    }
    $manifestPath = Join-Path $run 'artifacts.tsv'
    $manifest | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
    $proof = & (Join-Path $run ('feedback-host' + $exe)) $manifestPath 2>&1
    $proof | Tee-Object (Join-Path $run 'host-proof.txt')
    if ($LASTEXITCODE -ne 0 -or $proof -notcontains 'PASS native A1-B2-A3 conformance') { throw 'Dynamic host conformance failed' }
    $passed = $true
} finally {
    $whole.Stop()
    [ordered]@{ schema = 'rusty.quest.native-feedback-host-proof.v1'; passed = $passed;
        compiler_path = $compiler; compiler_sha256 = $compilerHash; source_hashes = $sourceHashes;
        artifacts = $artifacts; timings = $timings; full_elapsed_seconds = $whole.Elapsed.TotalSeconds;
        claim = 'host conformance only; no Android, Quest, JNI, unload or device performance proof' } |
        ConvertTo-Json -Depth 8 | Set-Content (Join-Path $run 'receipt.json') -Encoding utf8NoBOM
    Write-Output "Evidence: $run"
}
