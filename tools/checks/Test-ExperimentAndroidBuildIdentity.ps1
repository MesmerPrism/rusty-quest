param([Parameter(Mandatory)][string]$JsonJar,[Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new identity test output required.'}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$package='io/github/mesmerprism/rustyquest/native_renderer'
$classPath="$JsonJar$([IO.Path]::PathSeparator)$AndroidJar"
& javac --release 8 -encoding UTF-8 -cp $classPath -d $OutputRoot (Join-Path $repo "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/$package/ExperimentSessionBuildIdentity.java") (Join-Path $repo "apps/native-renderer-android/tests/java/$package/ExperimentSessionBuildIdentityTest.java")
if($LASTEXITCODE -ne 0){throw 'Build identity production helper compilation failed.'}
& java -cp "$OutputRoot$([IO.Path]::PathSeparator)$classPath" io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionBuildIdentityTest
if($LASTEXITCODE -ne 0){throw 'Build identity projection tests failed.'}
