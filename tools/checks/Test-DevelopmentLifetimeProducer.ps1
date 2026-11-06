param(
    [string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [Parameter(Mandatory)][string]$ManifoldRoot,
    [Parameter(Mandatory)][string]$OutputRoot,
    [string]$GradleHome='', [string]$JavaHome=''
)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
if(Test-Path -LiteralPath $OutputRoot){throw 'Producer proof output must be new.'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
Import-Module (Join-Path $RepoRoot 'tools/EmbeddedDuplexFreshSetupPolicy.psm1') -Force
$script:checks=0
function Need($Value){$script:checks++;if(-not$Value){throw "Producer control $script:checks failed."}}
function Refuse([scriptblock]$Action){try{&$Action|Out-Null;throw 'UNEXPECTED_ACCEPTANCE'}catch{if($_.Exception.Message-ceq'UNEXPECTED_ACCEPTANCE'){throw};$script:checks++}}
$composition=[pscustomobject]@{repositories=@([pscustomobject]@{role='path-dependency';repository=$ManifoldRoot})}
$selected=Resolve-EmbeddedDuplexDevelopmentLifetime development-v1 $composition 300000
$horizon=[long]$selected.horizon_ms
Need ($horizon-gt[int]::MaxValue-and$selected.horizon_ms-is[long])
Need ((Get-EmbeddedDuplexFreshSetupPolicy source-bound-v2 $selected.policy).credential_validity_ms-eq$horizon)
Need ((Get-EmbeddedDuplexFreshSetupPolicy source-bound-v2).credential_validity_ms-eq300000)
Need ((Resolve-EmbeddedDuplexDevelopmentLifetime finite $composition 1800000).horizon_ms-eq1800000)
Refuse {Resolve-EmbeddedDuplexDevelopmentLifetime finite $composition $horizon}
Refuse {Resolve-EmbeddedDuplexDevelopmentLifetime development-v1 ([pscustomobject]@{repositories=@()}) 300000}
Refuse {Resolve-EmbeddedDuplexDevelopmentLifetime development-v1 ([pscustomobject]@{repositories=@($composition.repositories[0],$composition.repositories[0])}) 300000}
$builder=Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
Need ($errors.Count-eq0)
# Execute only the REAL binder, never the builder body or any build/cache work.
$binder=[scriptblock]::Create($ast.ParamBlock.Extent.Text+[Environment]::NewLine+'return @{horizon=$EmbeddedDuplexSetupHorizonMs;type=$EmbeddedDuplexSetupHorizonMs.GetType().FullName;selection=$EmbeddedDuplexLifetimePolicy}')
$bound=&$binder -EmbeddedDuplexLifetimePolicy development-v1 -EmbeddedDuplexSetupHorizonMs $horizon
Need ($bound.horizon-eq$horizon-and$bound.type-ceq'System.Int64'-and$bound.selection-ceq'development-v1')
Refuse {&$binder -EmbeddedDuplexLifetimePolicy development-v1 -EmbeddedDuplexSetupHorizonMs ($horizon+1)}
$gradle='not_requested'
$gradleSource=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/app/build.gradle.kts'
if($GradleHome){
    Need ([bool]$JavaHome)
    $project=Join-Path $OutputRoot 'gradle';New-Item -ItemType Directory -Path $project|Out-Null
    $source=Get-Content -LiteralPath $gradleSource -Raw
    $start=$source.IndexOf('// One semantic policy');$end=$source.IndexOf('// Explicit app artifact policy',$start)
    Need ($start-ge0-and$end-gt$start)
    [IO.File]::WriteAllText((Join-Path $project 'settings.gradle.kts'),'rootProject.name = "development-lifetime-producer-proof"')
    [IO.File]::WriteAllText((Join-Path $project 'build.gradle.kts'),$source.Substring($start,$end-$start)+[Environment]::NewLine+'println("ACTUAL_POLICY_HORIZON=" + embeddedDuplexSetupHorizonMs.get())')
    $env:JAVA_HOME=$JavaHome;$env:GRADLE_USER_HOME=Join-Path $project 'user-home'
    $env:RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_BASE64=$selected.policy_base64
    $env:RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_SETUP_HORIZON_MS=$horizon.ToString([Globalization.CultureInfo]::InvariantCulture)
    & (Join-Path $GradleHome 'bin/gradle.bat') -p $project --no-daemon --offline help 1>(Join-Path $OutputRoot 'gradle.stdout') 2>(Join-Path $OutputRoot 'gradle.stderr')
    Need ($LASTEXITCODE-eq0)
    Need ((Get-Content -LiteralPath (Join-Path $OutputRoot 'gradle.stdout') -Raw).Contains("ACTUAL_POLICY_HORIZON=$horizon"))
    $gradle='passed_actual_source_block_only'
}
$pins=@($builder,$gradleSource,(Join-Path $RepoRoot 'tools/EmbeddedDuplexFreshSetupPolicy.psm1'),(Join-Path $ManifoldRoot 'crates/rusty-manifold-admission/contracts/development-lifetime-policy.v1.json'))|ForEach-Object{[ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}}
$result=[ordered]@{status='passed';checks=$checks;horizon_ms=$horizon;actual_powershell_binder_type=$bound.type;gradle=$gradle;source_pins=$pins;limits='Actual source policy helper, builder parameter binder, optional exact Gradle producer block in fresh project. No Android plugin, APK/native build, shared build-cache/device effects or qualified source graph claim.'}
$result|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Encoding utf8NoBOM
$result|ConvertTo-Json -Depth 8
