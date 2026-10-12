param([string]$RepoRoot=(Split-Path $PSScriptRoot -Parent),[string]$JavaHome=$env:JAVA_HOME,[string]$PythonExecutable='python',[string]$OutDir='')
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $RepoRoot).Path
if(-not$JavaHome){$javac=(Get-Command javac -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source;$JavaHome=Split-Path (Split-Path $javac -Parent) -Parent}
if(-not(Test-Path (Join-Path $JavaHome 'bin/javac.exe') -PathType Leaf)){throw 'Explicit producer JDK required'}
$python=(Get-Command $PythonExecutable -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
if(-not$OutDir){$OutDir=Join-Path $root ('target/owner-transport-producer-'+[guid]::NewGuid().ToString('N'))}
& $python -B (Join-Path $root 'tools/checks/Test-OwnerTransportDiagnosticHost.py') --repo-root $root --output-root $OutDir --java-home $JavaHome
if($LASTEXITCODE-ne0){throw 'Strict transport Java producer/real TCP controls failed'}
& $python -B (Join-Path $root 'tools/checks/Test-ReceiverCodecStartDiagnosticHost.py') --repo-root $root --output-root ($OutDir+'-codec-start') --java-home $JavaHome
if($LASTEXITCODE-ne0){throw 'Actual codec-start classifier/journal controls failed'}
