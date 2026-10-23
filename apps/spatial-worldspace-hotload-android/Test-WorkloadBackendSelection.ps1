# Execute the actual builder's pure backend selector; no Gradle, CMake, signing or device calls.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Fresh backend-selection test directory required'}
$builder=Join-Path $PSScriptRoot 'Build-SpatialWorldspaceHotload.ps1'
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors|Out-String)}
$selector=@($ast.FindAll({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Resolve-SpatialWorkloadBackend'},$true))
if($selector.Count -ne 1){throw 'Exactly one actual backend selector required'}
# Evaluate only the selected trusted owner function, never the builder entrypoint.
Invoke-Expression $selector[0].Extent.Text
function Rejects([scriptblock]$Action){$rejected=$false;try{& $Action|Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'Expected backend rejection'}}
$unavailable=Resolve-SpatialWorkloadBackend -SourceDirectory '' -SessionCore $false -ExternalWorkloadHotload $false
if($unavailable.selection -ne 'unavailable' -or $unavailable.source_directory -ne ''){throw 'Explicit absent-source backend selection mismatch'}
Rejects {Resolve-SpatialWorkloadBackend -SourceDirectory '' -SessionCore $true -ExternalWorkloadHotload $false}
Rejects {Resolve-SpatialWorkloadBackend -SourceDirectory '' -SessionCore $false -ExternalWorkloadHotload $true}
Rejects {Resolve-SpatialWorkloadBackend -SourceDirectory '' -SessionCore $true -ExternalWorkloadHotload $true}
New-Item -ItemType Directory -Path $output|Out-Null
$selected=Join-Path $output 'selected'
New-Item -ItemType Directory -Path $selected|Out-Null
[IO.File]::WriteAllText((Join-Path $selected 'CMakeLists.txt'),'# Test input; never executed')
foreach($mode in @(@($false,$false),@($true,$false),@($false,$true))){
    $value=Resolve-SpatialWorkloadBackend -SourceDirectory $selected -SessionCore $mode[0] -ExternalWorkloadHotload $mode[1]
    if($value.selection -ne 'external' -or $value.source_directory -ne (Resolve-Path -LiteralPath $selected).Path){throw 'Explicit selected module changed'}
}
Rejects {Resolve-SpatialWorkloadBackend -SourceDirectory (Join-Path $output 'missing') -SessionCore $false -ExternalWorkloadHotload $false}
Rejects {Resolve-SpatialWorkloadBackend -SourceDirectory $output -SessionCore $false -ExternalWorkloadHotload $false}
Rejects {Resolve-SpatialWorkloadBackend -SourceDirectory (Join-Path $selected 'CMakeLists.txt') -SessionCore $false -ExternalWorkloadHotload $false}
$source=[IO.File]::ReadAllText($builder)
if($source -notmatch 'external_workload_backend=\$workloadBackend.selection'){throw 'Backend selection missing from hashed input lock'}
if($source -notmatch '(?m)^if\(\$workloadBackend.selection -eq ''external''\)\{\$externalRoots\[''private-source''\]'){throw 'External inventory is not conditional'}
if($source -notmatch 'if\(\$workloadBackend.selection -eq ''external''\)\{\$gradleArguments\+=\(''-PworkloadExternalSource='){throw 'External CMake property is not conditional'}
$cmake=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'app/src/main/cpp/CMakeLists.txt'))
if($cmake -notmatch 'else\(\)\s+add_library\(rq_external_workload STATIC unavailable_workload.cpp\)'){throw 'Existing unavailable-workload backend missing'}
$fallback=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'app/src/main/cpp/unavailable_workload.cpp'))
if($fallback -notmatch 'throw std::runtime_error\("No external workload selected; real simulation unavailable"\)'){throw 'Unavailable backend must reject real workload creation'}
[ordered]@{status='passed';actual_selector_executed=$true;absent_source_unavailable=$true;session_and_reload_missing_source_rejected=$true;explicit_valid_source_preserved=$true;explicit_bad_source_rejected=$true;input_lock_inventory_property_guards=$true;gradle_operations=0;cmake_operations=0;device_operations=0}|ConvertTo-Json|Tee-Object -FilePath (Join-Path $output 'RESULT.json')
