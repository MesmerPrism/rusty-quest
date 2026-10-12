param(
 [string]$RepoRoot=(Split-Path $PSScriptRoot -Parent),
 [string]$PythonExecutable='python',
 [string]$RustcExecutable='rustc',
 [string]$OutDir=''
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $RepoRoot).Path
$python=(Get-Command $PythonExecutable -CommandType Application -ErrorAction Stop).Source
$rustc=(Get-Command $RustcExecutable -CommandType Application -ErrorAction Stop).Source
if(-not$OutDir){$OutDir=Join-Path $root ('target/stereo-source-refresh-host-'+[guid]::NewGuid().ToString('N'))}
& $python -B (Join-Path $root 'tools/checks/Test-StereoSourceRefreshHost.py') --repo-root $root --output-root $OutDir --rustc $rustc
if($LASTEXITCODE-ne0){throw 'Retired source refresh host controls failed'}
