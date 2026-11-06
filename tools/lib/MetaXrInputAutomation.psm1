#Requires -Version 7.6
# Finite owner-injected host rehearsal. No network/device commands or app actions live here.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:IdentityFields = @('package','apk_sha256','process_birth','layer_fingerprint','instance_id','session_id')

function Assert-InputIdentity($Expected, $Observed) {
    foreach ($field in $script:IdentityFields) {
        if ($Observed -isnot [System.Collections.IDictionary] -or -not $Observed.Contains($field) -or
            [string]$Expected[$field] -cne [string]$Observed[$field]) {
            throw "Operator identity changed or missing: $field"
        }
    }
}
function Assert-InputContext($Selection, $Identity, [string]$ProxyOrigin) {
    if (-not $Selection.enabled -or $Selection.package -cne $Identity.package -or
        $Selection.fingerprint -cne $Identity.layer_fingerprint) { throw 'Selected app/layer does not match runtime context.' }
    foreach ($field in $script:IdentityFields) {
        if (-not $Identity.Contains($field) -or [string]$Identity[$field] -cnotmatch '^[A-Za-z0-9_.:-]{1,160}$') { throw "Exact runtime identity required: $field" }
    }
    foreach ($field in @('apk_sha256','layer_fingerprint')) {
        if ($Identity[$field] -cnotmatch '^[0-9a-f]{64}$') { throw 'Exact APK and layer hashes required.' }
    }
    $origin = [Uri]::new($ProxyOrigin, [UriKind]::Absolute)
    if ($origin.Scheme -cne 'http' -or -not $origin.IsLoopback -or
        $origin.AbsolutePath -cne '/' -or $origin.Query -or $origin.Fragment -or $origin.UserInfo) {
        throw 'Use the selected loopback proxy bare origin, without endpoint suffix or credentials.'
    }
}
function Assert-InputFrame($Identity, $frame) {
    Assert-InputIdentity $Identity $frame.identity
    foreach ($flag in @('backend_ready','layer_loaded','focused')) {
        if ($frame[$flag] -isnot [bool] -or -not $frame[$flag]) { throw "Operator not ready: $flag" }
    }
    if (($frame.frame_index -isnot [long] -and $frame.frame_index -isnot [int]) -or $frame.frame_index -lt 0 -or
        ($frame.source_timestamp_ns -isnot [long] -and $frame.source_timestamp_ns -isnot [int]) -or $frame.source_timestamp_ns -lt 0 -or
        $frame.clock_domain -cne 'xr') { throw 'Bounded XR frame index/timestamp required.' }
}
function Test-QuestMetaXrInputReadiness {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Selection, [Parameter(Mandatory)][System.Collections.IDictionary]$Identity,
        [Parameter(Mandatory)][string]$ProxyOrigin, [Parameter(Mandatory)][object[]]$Frames)
    Assert-InputContext $Selection $Identity $ProxyOrigin
    if ($Frames.Count -lt 2 -or $Frames.Count -gt 5) { throw 'Readiness needs two to five successful same-session observations.' }
    $first = $null; $last = $null; $previous = $null
    foreach ($frame in $Frames) {
        Assert-InputFrame $Identity $frame
        if ($null -ne $previous -and ($frame.frame_index -lt $previous.frame_index -or $frame.source_timestamp_ns -lt $previous.source_timestamp_ns)) { throw 'Frame order regressed.' }
        if ($null -eq $first) { $first = $frame }
        $last = $frame; $previous = $frame
    }
    if ($last.frame_index -le $first.frame_index -or $last.source_timestamp_ns -le $first.source_timestamp_ns) { throw 'Latest-frame repetition does not establish advancement.' }
    [pscustomobject]@{ready=$true;observations=$Frames.Count;first_frame=$first.frame_index;last_frame=$last.frame_index;clock_domain='xr';widget_effect_confirmed=$false;rendered_hands_confirmed=$false;capture_confirmed=$false}
}
function Invoke-QuestMetaXrInputRehearsal {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Selection, [Parameter(Mandatory)][System.Collections.IDictionary]$Identity,
        [Parameter(Mandatory)][string]$ProxyOrigin,
        [Parameter(Mandatory)][ValidateSet('hand_pinch','controller_select')][string]$InputKind,
        [Parameter(Mandatory)][scriptblock]$ReadFrame,
        [Parameter(Mandatory)][scriptblock]$DispatchInput,
        [Parameter(Mandatory)][scriptblock]$ObserveOriginalEffect,
        [Parameter(Mandatory)][scriptblock]$ReleaseInput,
        [Parameter(Mandatory)][scriptblock]$ObserveTerminal,
        [ValidateRange(2,5)][int]$ObservationLimit=3)
    # Snapshot, never follow a callback's newer app/session. Readiness has no effects.
    $snapshot=[ordered]@{}; foreach($key in $script:IdentityFields){$snapshot[$key]=$Identity[$key]}
    $correlation=[guid]::NewGuid().ToString('N')
    function Copy-InputContext {
        $copy=[ordered]@{};foreach($key in $script:IdentityFields){$copy[$key]=$snapshot[$key]}
        [ordered]@{correlation_id=$correlation;identity=$copy;input_kind=$InputKind}
    }
    $context=Copy-InputContext
    $result=[ordered]@{schema='rusty.quest.meta_xr_input.rehearsal.v1';evidence_tier='host-rehearsal';context=$context;readiness=$null;
        dispatch_state='not_sent';original_effect_state='unknown';release_state='not_required';terminal_state='unknown';
        completed=$false;device_verified=$false;rendered_hands_confirmed=$false;capture_confirmed=$false;errors=@();receipts=[ordered]@{}}
    $attempted=$false; $phase='readiness'
    try {
        Assert-InputContext $Selection $snapshot $ProxyOrigin
        $frames=[Collections.Generic.List[object]]::new()
        for($index=0;$index -lt $ObservationLimit;$index++) {
            $frame=& $ReadFrame (Copy-InputContext)
            Assert-InputFrame $snapshot $frame
            $frames.Add($frame)
            # A known successful repeated latest frame is allowed only within this finite budget.
        }
        $result.readiness=Test-QuestMetaXrInputReadiness -Selection $Selection -Identity $snapshot -ProxyOrigin $ProxyOrigin -Frames $frames.ToArray()
        $phase='dispatch'; $attempted=$true; $result.dispatch_state='outcome_unknown'; $result.release_state='pending'
        $receipt=& $DispatchInput (Copy-InputContext)
        Assert-InputIdentity $snapshot $receipt.identity
        if($receipt.correlation_id -cne $context.correlation_id){throw 'Dispatch correlation differs.'}
        $result.receipts.dispatch=$receipt
        if($receipt.status -cne 'delivered'){throw 'Dispatch did not confirm input delivery; original outcome retained.'}
        $result.dispatch_state='delivered'; $phase='original_effect'
        $receipt=& $ObserveOriginalEffect (Copy-InputContext)
        Assert-InputIdentity $snapshot $receipt.identity
        if($receipt.correlation_id -cne $context.correlation_id -or $receipt.status -cnotin @('confirmed','pending','rejected')){throw 'Original effect receipt does not join this attempt.'}
        if($receipt.status -ceq 'confirmed' -and [string]::IsNullOrWhiteSpace($receipt.evidence_ref)){throw 'Original app effect needs its own evidence reference.'}
        $result.original_effect_state=$receipt.status; $result.receipts.original_effect=$receipt
    } catch {
        $result.errors+=@{phase=$phase;message=$_.Exception.Message}
    } finally {
        if($attempted) {
            try {
                # Release the original target even if dispatch threw or its result was lost. Never replay input.
                $receipt=& $ReleaseInput (Copy-InputContext)
                Assert-InputIdentity $snapshot $receipt.identity
                if($receipt.correlation_id -cne $context.correlation_id -or $receipt.status -cne 'released' -or
                    $receipt.overrides_active -isnot [bool] -or $receipt.overrides_active -or
                    $receipt.buttons_down -isnot [bool] -or $receipt.buttons_down -or
                    [string]::IsNullOrWhiteSpace($receipt.evidence_ref)){throw 'Original override/button release needs effective owner evidence.'}
                $result.release_state='confirmed'; $result.receipts.release=$receipt
            } catch { $result.release_state='outcome_unknown'; $result.errors+=@{phase='release';message=$_.Exception.Message} }
            try {
                $receipt=& $ObserveTerminal (Copy-InputContext)
                Assert-InputIdentity $snapshot $receipt.identity
                if($receipt.correlation_id -cne $context.correlation_id -or $receipt.status -cnotin @('confirmed','pending','unknown')){throw 'Terminal observation does not join original target.'}
                if($receipt.status -ceq 'confirmed' -and [string]::IsNullOrWhiteSpace($receipt.evidence_ref)){throw 'Terminal state needs owner evidence, not process absence alone.'}
                $result.terminal_state=$receipt.status; $result.receipts.terminal=$receipt
            } catch { $result.errors+=@{phase='terminal';message=$_.Exception.Message} }
        }
    }
    $result.completed=$result.dispatch_state -ceq 'delivered' -and $result.original_effect_state -ceq 'confirmed' -and
        $result.release_state -ceq 'confirmed' -and $result.terminal_state -ceq 'confirmed' -and $result.errors.Count -eq 0
    [pscustomobject]$result
}
Export-ModuleMember -Function Test-QuestMetaXrInputReadiness,Invoke-QuestMetaXrInputRehearsal
