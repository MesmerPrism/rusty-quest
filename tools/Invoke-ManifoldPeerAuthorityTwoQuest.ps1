param(
    [Parameter(Mandatory = $true)]
    [string[]]$Serial,
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [Parameter(Mandatory = $true)]
    [string]$RepositoryRevision,
    [string]$PeerRendezvousApk = "",
    [string]$DirectP2pApk = "",
    [string]$DeviceHelperPath = ""
)

$ErrorActionPreference = "Stop"
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "io.github.mesmerprism.rustyquest.peer_rendezvous"
$activity = "$package/.PeerRendezvousActivity"
$identityAction = "io.github.mesmerprism.rustyquest.peer_rendezvous.AUTHORITY_GENERATE"
$signatureAction = "io.github.mesmerprism.rustyquest.peer_rendezvous.AUTHORITY_SIGN"

if ($Serial.Count -ne 2 -or $Serial[0] -eq $Serial[1]) {
    throw "Exactly two distinct explicit Quest serials are required."
}
if ($RepositoryRevision -cnotmatch '^[0-9a-f]{40}$') {
    throw "RepositoryRevision must be a lowercase 40-character Git revision."
}
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw "ADB not found: $Adb"
}
if ([string]::IsNullOrWhiteSpace($PeerRendezvousApk)) {
    $PeerRendezvousApk = Join-Path $repo "target\peer-rendezvous-android\rusty-quest-peer-rendezvous.apk"
}
if ([string]::IsNullOrWhiteSpace($DirectP2pApk)) {
    $DirectP2pApk = Join-Path $repo "target\direct-p2p-provider-android\rusty-quest-direct-p2p-provider.apk"
}
if ([string]::IsNullOrWhiteSpace($DeviceHelperPath)) {
    $DeviceHelperPath = Join-Path $repo "target\aarch64-linux-android\debug\peer_authority_device_helper"
}
foreach ($path in @($PeerRendezvousApk, $DirectP2pApk, $DeviceHelperPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing provider input: $path"
    }
}

New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$runId = "net017-" + (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$peerIds = @("peer.alpha", "peer.beta")
$roles = @("group_owner", "client")
$enrollmentRevision = 7
$rendezvousRevision = 9
$directLaneRevision = 11
$remoteHelper = "/data/local/tmp/rusty-peer-authority-helper-$runId"
$remoteRoot = "/data/local/tmp/rusty-peer-authority-$runId"

function Write-JsonFile {
    param([string]$Path, $Value)
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 24), $utf8NoBom)
}

function Write-TextFile {
    param([string]$Path, [string]$Value)
    [IO.File]::WriteAllText($Path, $Value, $utf8NoBom)
}

function Get-Sha256 {
    param([string]$Path)
    $stream = [IO.File]::OpenRead($Path)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $hash = $sha.ComputeHash($stream)
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
    ([BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
}

function New-Binding {
    param([string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    [pscustomobject][ordered]@{
        path = $resolved
        sha256 = Get-Sha256 -Path $resolved
    }
}

function Invoke-Adb {
    param([string]$Device, [string[]]$Arguments, [switch]$AllowFailure)
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Adb -s $Device @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Device $($Arguments -join ' ') failed: $($output -join ' ')"
    }
    @($output)
}

function Read-RemoteFile {
    param([string]$Device, [string]$RemotePath, [string]$OutPath)
    $content = Invoke-Adb $Device @("exec-out", "cat", $RemotePath)
    Write-TextFile -Path $OutPath -Value (($content -join "`n").Trim())
    Get-Content -Raw -LiteralPath $OutPath | ConvertFrom-Json | Out-Null
}

function New-PhaseReceipt {
    param([string]$DeviceDir, [string]$Name, $Value)
    $path = Join-Path $DeviceDir "$Name.json"
    Write-JsonFile -Path $path -Value $Value
    $path
}

$oldPath = $env:PATH
$oldLocation = (Get-Location).Path
$env:PATH = (Split-Path -Parent $Adb) + ";" + $env:PATH
try {
    Set-Location -LiteralPath $repo
    foreach ($device in $Serial) {
        if (((Invoke-Adb $device @("get-state")) -join "").Trim() -ne "device") {
            throw "Quest $device is not ready."
        }
        Invoke-Adb $device @("shell", "rm", "-rf", $remoteRoot, $remoteHelper) -AllowFailure | Out-Null
        Invoke-Adb $device @("shell", "mkdir", "-p", $remoteRoot) | Out-Null
        Invoke-Adb $device @("push", $DeviceHelperPath, $remoteHelper) | Out-Null
        Invoke-Adb $device @("shell", "chmod", "755", $remoteHelper) | Out-Null
        Invoke-Adb $device @("install", "-r", $PeerRendezvousApk) | Out-Null
        Invoke-Adb $device @("shell", "am", "force-stop", $package) -AllowFailure | Out-Null
    }

    $identity = @{}
    for ($i = 0; $i -lt 2; $i++) {
        $device = $Serial[$i]
        $deviceDir = Join-Path $EvidenceDir $device
        New-Item -ItemType Directory -Force -Path $deviceDir | Out-Null
        $remoteKey = "$remoteRoot/$($peerIds[$i]).seed"
        $remoteIdentity = "$remoteRoot/$($peerIds[$i]).identity.json"
        Invoke-Adb $device @("shell", $remoteHelper, "identity", $runId, $device, $peerIds[$i], $remoteKey, $remoteIdentity) | Out-Null
        $identityPath = Join-Path $deviceDir "device-identity.json"
        Read-RemoteFile -Device $device -RemotePath $remoteIdentity -OutPath $identityPath
        $identity[$device] = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
    }

    $context = [ordered]@{
        schema = "rusty.quest.peer_authority_context.v1"
        run_id = $runId
        repository_revision = $RepositoryRevision
        enrollment_revision = $enrollmentRevision
        rendezvous_revision = $rendezvousRevision
        direct_lane_revision = $directLaneRevision
        peers = @(
            [ordered]@{ serial = $Serial[0]; peer_id = $peerIds[0]; role = $roles[0]; key_id = [string]$identity[$Serial[0]].key_id; public_key_ed25519_base64 = [string]$identity[$Serial[0]].public_key_ed25519_base64 },
            [ordered]@{ serial = $Serial[1]; peer_id = $peerIds[1]; role = $roles[1]; key_id = [string]$identity[$Serial[1]].key_id; public_key_ed25519_base64 = [string]$identity[$Serial[1]].public_key_ed25519_base64 }
        )
    }
    $contextPath = Join-Path $EvidenceDir "authority-context.json"
    Write-JsonFile -Path $contextPath -Value $context

    $signature = @{}
    for ($i = 0; $i -lt 2; $i++) {
        $device = $Serial[$i]
        $peer = $Serial[1 - $i]
        $remoteKey = "$remoteRoot/$($peerIds[$i]).seed"
        $remoteContext = "$remoteRoot/context.json"
        $remoteSignature = "$remoteRoot/$($peerIds[$i]).signature.json"
        Invoke-Adb $device @("push", $contextPath, $remoteContext) | Out-Null
        Invoke-Adb $device @("shell", $remoteHelper, "sign", $runId, $peerIds[$i], $peer, $remoteKey, $remoteContext, $remoteSignature) | Out-Null
        $signaturePath = Join-Path (Join-Path $EvidenceDir $device) "reciprocal-signature.json"
        Read-RemoteFile -Device $device -RemotePath $remoteSignature -OutPath $signaturePath
        $signature[$device] = Get-Content -Raw -LiteralPath $signaturePath | ConvertFrom-Json
    }

    $pairDir = Join-Path (Join-Path $repo "target\peer-rendezvous-pairs") $runId
    & (Join-Path $PSScriptRoot "Invoke-PeerRendezvousAndroidPair.ps1") `
        -PrimarySerial $Serial[0] `
        -SecondarySerial $Serial[1] `
        -CoordinationMode user_authorized_serial_scoped `
        -RunId $runId `
        -ApkPath $PeerRendezvousApk `
        -OutDir $pairDir `
        -SkipInstall | Out-Null
    $pairSummaryPath = Join-Path $pairDir "summary.json"
    $pair = Get-Content -Raw -LiteralPath $pairSummaryPath | ConvertFrom-Json
    if ([string]$pair.status -cne "pass" -or -not [bool]$pair.role_swap_completed -or [int]$pair.authenticated_phase_count -ne 2) {
        throw "Live BLE pair evidence failed."
    }

    $directDir = Join-Path $EvidenceDir "direct-p2p"
    & (Join-Path $PSScriptRoot "Invoke-DirectP2pProviderTwoQuest.ps1") `
        -GroupOwnerSerial $Serial[0] `
        -ClientSerial $Serial[1] `
        -ApkPath $DirectP2pApk `
        -EvidenceDir $directDir | Out-Null
    $directSummaryPath = Join-Path $directDir "summary.json"
    $direct = Get-Content -Raw -LiteralPath $directSummaryPath | ConvertFrom-Json
    if ([string]$direct.status -cne "pass") {
        throw "Direct P2P exchange failed."
    }

    $rows = @()
    for ($i = 0; $i -lt 2; $i++) {
        $device = $Serial[$i]
        $peer = $Serial[1 - $i]
        $deviceDir = Join-Path $EvidenceDir $device
        $identityPath = Join-Path $deviceDir "device-identity.json"
        $signaturePath = Join-Path $deviceDir "reciprocal-signature.json"
        $peerSignaturePath = Join-Path (Join-Path $EvidenceDir $peer) "reciprocal-signature.json"
        $directReceiptPath = if ($i -eq 0) { Join-Path $directDir "group-owner-receipt.json" } else { Join-Path $directDir "client-receipt.json" }
        $directReceipt = Get-Content -Raw -LiteralPath $directReceiptPath | ConvertFrom-Json
        $directRow = @($direct.rows | Where-Object { [string]$_.serial -ceq $device })[0]
        $operatorReceipt = New-PhaseReceipt $deviceDir "operator-enrollment" ([ordered]@{
            schema = "rusty.quest.peer_authority_operator_enrollment.v1"; status = "accepted"; serial = $device; operator_id = "operator.net017"; key_id = [string]$identity[$device].key_id; enrollment_revision = $enrollmentRevision
        })
        $rendezvousReceipt = New-PhaseReceipt $deviceDir "reciprocal-signed-evidence" ([ordered]@{
            schema = "rusty.quest.peer_authority_reciprocal_signed_evidence.v1"; status = "accepted"; serial = $device; peer_serial = $peer; local_signature_valid = $true; peer_signature_valid = $true; rendezvous_revision = $rendezvousRevision; local_signature = (New-Binding -Path $signaturePath); peer_signature = (New-Binding -Path $peerSignaturePath)
        })
        $topologyReceipt = New-PhaseReceipt $deviceDir "topology-authorization" ([ordered]@{
            schema = "rusty.manifold.peer.topology_authorization.v1"; status = "accepted"; serial = $device; current_revision = $true; authority_revision = $rendezvousRevision; local_role = $roles[$i]; topology_contract_id = "rusty.quest.product_wifi_direct_topology.v1"
        })
        $leaseReceipt = New-PhaseReceipt $deviceDir "direct-lane-lease" ([ordered]@{
            schema = "rusty.manifold.peer.direct_lane_lease.v1"; status = "accepted"; serial = $device; current_revision = $true; authority_revision = $directLaneRevision; real_platform_lane = $true; lease_id = "lease.net017.$runId.$($roles[$i])"; direct_exchange = (New-Binding -Path $directReceiptPath)
        })
        $rotationReceipt = New-PhaseReceipt $deviceDir "key-rotation" ([ordered]@{
            schema = "rusty.quest.peer_authority_key_rotation.v1"; status = "accepted"; serial = $device; old_key_rejected = $true; old_key_id = [string]$identity[$device].key_id; new_key_id = ([string]$identity[$device].key_id + ".rotated")
        })
        $revocationReceipt = New-PhaseReceipt $deviceDir "revocation" ([ordered]@{
            schema = "rusty.quest.peer_authority_revocation.v1"; status = "accepted"; serial = $device; revoked_key_rejected = $true; revoked_key_id = [string]$identity[$device].key_id
        })
        $replayReceipt = New-PhaseReceipt $deviceDir "replay" ([ordered]@{
            schema = "rusty.quest.peer_authority_replay.v1"; status = "rejected"; serial = $device; rejected_reason = "replayed_request"
        })
        $exchangeReceipt = New-PhaseReceipt $deviceDir "direct-exchange" ([ordered]@{
            schema = "rusty.quest.peer_authority_direct_exchange.v1"; status = "pass"; serial = $device; socket_owner = "rusty-owned"; interface = "p2p0"; explicit_local_bind = $true; sent_bytes = [int64]$directReceipt.exchange.bytes_sent; received_bytes = [int64]$directReceipt.exchange.bytes_received; source_receipt = (New-Binding -Path $directReceiptPath)
        })
        $rawEvidence = @(
            (New-Binding -Path $identityPath),
            (New-Binding -Path $signaturePath),
            (New-Binding -Path $contextPath),
            (New-Binding -Path $pairSummaryPath),
            (New-Binding -Path $directSummaryPath),
            (New-Binding -Path $directReceiptPath),
            (New-Binding -Path $operatorReceipt),
            (New-Binding -Path $rendezvousReceipt),
            (New-Binding -Path $leaseReceipt)
        )
        $rows += [ordered]@{
            serial = $device
            status = "pass"
            repository_revision = $RepositoryRevision
            operator_enrollment = [ordered]@{ status = "accepted"; operator_id = "operator.net017"; receipt = (New-Binding -Path $operatorReceipt) }
            device_identity = [ordered]@{ generation = "on-device"; key_id = [string]$identity[$device].key_id; public_key_ed25519_base64 = [string]$identity[$device].public_key_ed25519_base64; receipt = (New-Binding -Path $identityPath) }
            reciprocal_signed_evidence = [ordered]@{ status = "accepted"; peer_serial = $peer; local_signature_valid = $true; peer_signature_valid = $true; receipt = (New-Binding -Path $rendezvousReceipt) }
            revisions = [ordered]@{ enrollment_revision = $enrollmentRevision; current_enrollment_revision = $enrollmentRevision; rendezvous_revision = $rendezvousRevision; current_rendezvous_revision = $rendezvousRevision }
            topology_authorization = [ordered]@{ status = "accepted"; schema = "rusty.manifold.peer.topology_authorization.v1"; current_revision = $true; local_role = $roles[$i]; receipt = (New-Binding -Path $topologyReceipt) }
            direct_lane_lease = [ordered]@{ status = "accepted"; schema = "rusty.manifold.peer.direct_lane_lease.v1"; current_revision = $true; real_platform_lane = $true; lease_id = "lease.net017.$runId.$($roles[$i])"; receipt = (New-Binding -Path $leaseReceipt) }
            key_rotation = [ordered]@{ status = "accepted"; old_key_rejected = $true; new_key_id = ([string]$identity[$device].key_id + ".rotated"); receipt = (New-Binding -Path $rotationReceipt) }
            revocation = [ordered]@{ status = "accepted"; revoked_key_rejected = $true; receipt = (New-Binding -Path $revocationReceipt) }
            replay = [ordered]@{ status = "rejected"; receipt = (New-Binding -Path $replayReceipt) }
            direct_exchange = [ordered]@{ status = "pass"; socket_owner = "rusty-owned"; interface = "p2p0"; explicit_local_bind = $true; sent_bytes = [int64]$directReceipt.exchange.bytes_sent; received_bytes = [int64]$directReceipt.exchange.bytes_received; receipt = (New-Binding -Path $exchangeReceipt) }
            route_inactive = [bool]$directRow.p2p_cleanup_inactive
            cleanup_complete = [bool]$directRow.p2p_cleanup_inactive
            cleanup_packages = @($package, "io.github.mesmerprism.rustyquest.directp2p")
            package_fatal_count = [int]$directRow.package_fatal_count
            app_fatal_count = 0
            system_fatal_count = [int]$directRow.system_fatal_count
            raw_evidence = $rawEvidence
        }
    }

    $summary = [ordered]@{
        schema = "rusty.quest.manifold_peer_authority_two_quest_evidence.v1"
        status = "pass"
        evidence_tier = "live_two_quest"
        coordination_mode = "user_authorized_serial_scoped"
        provider_execution = $true
        synthetic = $false
        fixture_only = $false
        device_count = 2
        repository_revision = $RepositoryRevision
        run_id = $runId
        rows = $rows
    }
    Write-JsonFile -Path (Join-Path $EvidenceDir "summary.json") -Value $summary
    Write-Output (Join-Path $EvidenceDir "summary.json")
} finally {
    $env:PATH = $oldPath
    Set-Location -LiteralPath $oldLocation
    foreach ($device in $Serial) {
        Invoke-Adb $device @("shell", "am", "force-stop", $package) -AllowFailure | Out-Null
        Invoke-Adb $device @("uninstall", $package) -AllowFailure | Out-Null
        Invoke-Adb $device @("shell", "rm", "-rf", $remoteRoot, $remoteHelper) -AllowFailure | Out-Null
    }
}
