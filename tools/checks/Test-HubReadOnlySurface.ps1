param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required'}
$null=New-Item -ItemType Directory $OutputRoot
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$root=Join-Path $repo 'apps/manifold-broker-android'
$package='io/github/mesmerprism/rustymanifold/broker'
$jar=Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json') -Recurse -Filter 'json-*.jar'|Sort-Object FullName -Descending|Select-Object -First 1
if(-not$jar){throw 'Existing owner org.json test dependency required'}
$sources=@('ConnectionHubProtocol','HubProviderIdentity','HubSurfaceDescriptor')|ForEach-Object{Join-Path $root "src/main/java/$package/$_.java"}
$sources+=Join-Path $root "tests/java/$package/HubReadOnlySurfaceTest.java"
& javac --release 8 -cp $jar.FullName -d $OutputRoot @sources > (Join-Path $OutputRoot 'compile.stdout') 2> (Join-Path $OutputRoot 'compile.stderr')
$code=$LASTEXITCODE;[IO.File]::WriteAllText((Join-Path $OutputRoot 'compile.exit'),[string]$code)
if($code){throw 'Actual descriptor compile failed'}
& java -cp "$OutputRoot;$($jar.FullName)" io.github.mesmerprism.rustymanifold.broker.HubReadOnlySurfaceTest > (Join-Path $OutputRoot 'tests.stdout') 2> (Join-Path $OutputRoot 'tests.stderr')
$code=$LASTEXITCODE;[IO.File]::WriteAllText((Join-Path $OutputRoot 'tests.exit'),[string]$code)
if($code){throw 'Read-only descriptor controls failed'}
Get-Content (Join-Path $OutputRoot 'tests.stdout')
@{passed=$true;jar=@{path=$jar.FullName;sha256=(Get-FileHash $jar.FullName).Hash.ToLowerInvariant()};limits='Actual pure descriptor; modeled OS identity; no socket/network/Binder/runtime authority'}|ConvertTo-Json -Depth 4|Set-Content (Join-Path $OutputRoot 'RESULT.json')
