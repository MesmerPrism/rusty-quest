param([string]$RepoRoot=(Split-Path $PSScriptRoot -Parent),[string]$PythonExecutable='python',[string]$RustcExecutable='rustc')
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $RepoRoot).Path
$out=Join-Path $root ('target/primary-continuation-'+[guid]::NewGuid().ToString('N'))
& $PythonExecutable -B (Join-Path $root 'tools/checks/Test-StereoPrimaryInputHost.py') --repo-root $root --output-root $out --rustc $RustcExecutable
if($LASTEXITCODE -ne 0){throw 'Actual guide continuation controls failed'}
& (Join-Path $root 'tools/checks/Test-PrimaryInputBuildClosure.ps1') -RepoRoot $root -OutputRoot (Join-Path $root ('target/primary-build-closure-'+[guid]::NewGuid().ToString('N')))
