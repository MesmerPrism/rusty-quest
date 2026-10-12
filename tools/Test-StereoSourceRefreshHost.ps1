param(
 [string]$RepoRoot=(Split-Path $PSScriptRoot -Parent),
 [string]$PythonExecutable='python',
 [string]$RustcExecutable='rustc',
 [string]$OutDir='',
 [string]$KotlinCompilerClasspath='',
 [string]$KotlinRuntimeClasspath='',
 [string]$JavaExecutable='java'
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $RepoRoot).Path
$python=(Get-Command $PythonExecutable -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$rustc=(Get-Command $RustcExecutable -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
if(-not$OutDir){$OutDir=Join-Path $root ('target/stereo-source-refresh-host-'+[guid]::NewGuid().ToString('N'))}
& $python -B (Join-Path $root 'tools/checks/Test-StereoSourceRefreshHost.py') --repo-root $root --output-root $OutDir --rustc $rustc
if($LASTEXITCODE-ne0){throw 'Retired source refresh host controls failed'}

$terminalOut=Join-Path $root ('target/stereo-renderer-terminal-host-'+[guid]::NewGuid().ToString('N'))
& $python -B (Join-Path $root 'tools/checks/Test-StereoRendererTerminalHost.py') --repo-root $root --output-root $terminalOut --rustc $rustc --native-only
if($LASTEXITCODE-ne0){throw 'Renderer terminal native/source controls failed'}

if($KotlinCompilerClasspath -or $KotlinRuntimeClasspath){
 if(-not $KotlinCompilerClasspath -or -not $KotlinRuntimeClasspath){throw 'Both explicit Kotlin classpaths required'}
 $java=(Get-Command $JavaExecutable -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
 $cpuOut=Join-Path $root ('target/neutral-mask-configuration-host-'+[guid]::NewGuid().ToString('N'))
 & $python -B (Join-Path $root 'tools/checks/Test-NeutralMaskConfigurationHost.py') --repo-root $root --output-root $cpuOut --java $java --compiler-classpath $KotlinCompilerClasspath --runtime-classpath $KotlinRuntimeClasspath
 if($LASTEXITCODE-ne0){throw 'CPU configuration producer controls failed'}
}
