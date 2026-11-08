param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)), [Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$targetPrefix = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'target')).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $OutputRoot.StartsWith($targetPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be a child of the selected repository target' }
if (Test-Path -LiteralPath $OutputRoot) { throw 'Create-new output required' }
$null = New-Item -ItemType Directory -Path $OutputRoot
Import-Module (Join-Path $RepoRoot 'tools/lib/SpatialUsbAdbRelayBuild.psm1') -Force
$script:checks = 0
function Check($Value, [string]$Message) { if (-not $Value) { throw $Message }; $script:checks++ }
function Refuses([scriptblock]$Body) { try { & $Body | Out-Null } catch { $script:checks++; return }; throw 'Expected selection refusal' }
function Resource([string]$Name, [string]$Text, [string]$Folder = 'values') {
    $root = Join-Path $OutputRoot $Name
    $directory = Join-Path $root $Folder
    $null = New-Item -ItemType Directory -Path $directory -Force
    [IO.File]::WriteAllText((Join-Path $directory 'usb.xml'), $Text)
    return $root
}
$name = 'rusty_quest_embedded_duplex_usb_adb_relay_enabled'
$enabled = Resource 'enabled' "<resources><integer name='$name'>1</integer></resources>"
$disabled = Resource 'disabled' "<resources><integer name='$name'>0</integer></resources>"
$unrelated = Resource 'unrelated' '<resources><integer name="other_flag">1</integer></resources>'
Check (-not (Resolve-SpatialUsbAdbRelayBuildSelection -BuildType Debug -EmbeddedProductSelected)) 'Absent resource must retain ordinary selection'
Check (-not (Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $disabled -BuildType Release -EmbeddedProductSelected)) 'Explicit zero must remain inert in Release'
Check (-not (Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $unrelated -BuildType Debug -EmbeddedProductSelected)) 'Unrelated resource must not select USB'
Check (Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $enabled -BuildType Debug -EmbeddedProductSelected) 'Explicit Debug capability must compile USB support'
Refuses { Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $enabled -BuildType Release -EmbeddedProductSelected }
Refuses { Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $enabled -BuildType Debug -PublicationBuild -EmbeddedProductSelected }
Refuses { Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $enabled -BuildType Debug }
$bad = Resource 'bad' "<resources><integer name='$name'>2</integer></resources>"
Refuses { Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $bad -BuildType Debug -EmbeddedProductSelected }
$qualified = Resource 'qualified' "<resources><integer name='$name'>1</integer></resources>" 'values-v35'
Refuses { Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $qualified -BuildType Debug -EmbeddedProductSelected }
$duplicate = Resource 'duplicate' "<resources><integer name='$name'>1</integer></resources>"
$null = New-Item -ItemType Directory -Path (Join-Path $duplicate 'values-v35')
[IO.File]::WriteAllText((Join-Path $duplicate 'values-v35/other.xml'), "<resources><integer name='$name'>0</integer></resources>")
Refuses { Resolve-SpatialUsbAdbRelayBuildSelection -ResourceDirectory $duplicate -BuildType Debug -EmbeddedProductSelected }
$descriptor = [ordered]@{ retained = 'original' }
Set-SpatialUsbAdbRelayBuildDescriptor -Descriptor $descriptor -Enabled:$false
Check ($descriptor.Count -eq 1) 'Unselected descriptor shape must remain unchanged'
Set-SpatialUsbAdbRelayBuildDescriptor -Descriptor $descriptor -Enabled:$true
Check ($descriptor.development_usb_adb_relay.enabled -eq $true -and $descriptor.retained -ceq 'original') 'Selected capability must join build and native identities'
Check ((@(Get-SpatialUsbAdbRelayCargoArguments -Enabled:$true) -join ' ') -ceq '--features development-usb-adb-relay') 'Cargo feature must be explicit'
Check (@(Get-SpatialUsbAdbRelayCargoArguments -Enabled:$false).Count -eq 0) 'Ordinary build must not enable Cargo capability'
[ordered]@{ status = 'passed'; assertions = $script:checks; scope = 'Actual resource/build selection, descriptor and Cargo argument owner; no APK or device effects' } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Encoding utf8
Write-Output "USB relay build selection controls passed: $script:checks"
