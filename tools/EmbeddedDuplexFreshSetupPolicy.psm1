Set-StrictMode -Version Latest

# Exact closed subset of Manifold fresh_history_canonical: ordinal ASCII keys,
# ordered arrays, signed64 integer JSON, no floats, escaping or surrogate input.
# Every string in these two typed records is restricted to printable identifier/
# hexadecimal spelling; arbitrary JSON values are not an authority input here.
function ConvertTo-FreshSetupCanonicalJson {
    param([AllowNull()]$Value)
    if($null -eq $Value){return 'null'}
    if($Value -is [string]){
        if($Value -cnotmatch '^[\x20-\x21\x23-\x5b\x5d-\x7e]*$'){throw 'Fresh build canonical string is outside the closed ASCII subset.'}
        return '"'+$Value+'"'
    }
    if($Value -is [bool]){return $Value.ToString().ToLowerInvariant()}
    if($Value -is [byte]-or$Value -is [short]-or$Value -is [int]-or$Value -is [long]){return ([long]$Value).ToString([Globalization.CultureInfo]::InvariantCulture)}
    if($Value -is [System.Collections.IDictionary]){
        $keys=[string[]]@($Value.get_Keys());[Array]::Sort($keys,[StringComparer]::Ordinal)
        return '{'+(($keys|ForEach-Object{(ConvertTo-FreshSetupCanonicalJson ([string]$_))+':'+(ConvertTo-FreshSetupCanonicalJson $Value[$_])})-join',')+'}'
    }
    if($Value -is [System.Collections.IEnumerable]){return '['+(@($Value|ForEach-Object{ConvertTo-FreshSetupCanonicalJson $_})-join',')+']'}
    if($Value -is [pscustomobject]){
        $map=[ordered]@{};foreach($p in $Value.PSObject.Properties){$map[$p.Name]=$p.Value};return ConvertTo-FreshSetupCanonicalJson $map
    }
    throw 'Fresh build canonical value is not a closed signed64 JSON value.'
}
function Get-FreshSetupDomainSha256 {
    param([string]$Domain,$Value)
    $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes($Domain+[char]0+(ConvertTo-FreshSetupCanonicalJson $Value))
    return 'sha256:'+([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))).ToLowerInvariant()
}
function Assert-FreshSetupProperties {
    param($Object,[string[]]$Names)
    $actual=[string[]]@($Object.PSObject.Properties.Name);[Array]::Sort($actual,[StringComparer]::Ordinal)
    $expected=[string[]]$Names.Clone();[Array]::Sort($expected,[StringComparer]::Ordinal)
    if(($actual-join'|')-cne($expected-join'|')){throw 'Fresh build record has missing/extra/case-changed fields.'}
}
function Assert-FreshSetupNoDuplicateJson {
    param([System.Text.Json.JsonElement]$Element)
    if($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object){
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($p in $Element.EnumerateObject()){if(-not$seen.Add($p.Name)){throw 'Duplicate fresh target JSON key.'};Assert-FreshSetupNoDuplicateJson $p.Value}
    }elseif($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array){foreach($v in $Element.EnumerateArray()){Assert-FreshSetupNoDuplicateJson $v}}
}
# Resolve the public semantic contract from the actual Cargo source composition.
# No command supplies a policy document or an arbitrary development horizon.
function Resolve-EmbeddedDuplexDevelopmentLifetime {
    param([string]$Selection, $SourceComposition, [long]$FiniteHorizonMs)
    if($Selection -ceq 'finite') {
        if($FiniteHorizonMs -lt 300000 -or $FiniteHorizonMs -gt 3600000 -or $FiniteHorizonMs % 60000 -ne 0){throw 'Finite setup horizon must be whole minutes in 300000..3600000 ms.'}
        return [pscustomobject]@{horizon_ms=$FiniteHorizonMs;policy=$null;policy_json='';policy_base64=''}
    }
    if($Selection -cne 'development-v1'){throw 'Unsupported lifetime selection.'}
    $contracts=@($SourceComposition.repositories | Where-Object {$_.role -ceq 'path-dependency'} | ForEach-Object {
        $candidate=Join-Path ([string]$_.repository) 'crates/rusty-manifold-admission/contracts/development-lifetime-policy.v1.json'
        if(Test-Path -LiteralPath $candidate -PathType Leaf){$candidate}
    })
    if($contracts.Count -ne 1){throw 'Development lifetime requires one actual Manifold policy source dependency.'}
    $policy=Get-Content -LiteralPath $contracts[0] -Raw | ConvertFrom-Json -Depth 8
    Assert-FreshSetupProperties $policy @('$schema','policy_id','delegated_authority_horizon_ms')
    $horizon=$policy.delegated_authority_horizon_ms
    if($policy.'$schema' -cne 'rusty.manifold.development.lifetime_policy.v1' -or $policy.policy_id -cne 'policy.manifold.development-lifetime.v1' -or
        $horizon -isnot [long] -and $horizon -isnot [int] -or [long]$horizon -le 3600000 -or [long]$horizon % 60000 -ne 0){throw 'Invalid source-owned development lifetime contract.'}
    $canonical=ConvertTo-FreshSetupCanonicalJson $policy
    return [pscustomobject]@{horizon_ms=[long]$horizon;policy=$policy;policy_json=$canonical;policy_base64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($canonical))}
}
function Get-EmbeddedDuplexFreshSetupPolicy {
    param([string]$Selection,[AllowNull()]$DevelopmentLifetimePolicy)
    if($Selection -ceq 'none'){return $null}
    if($Selection -cne 'source-bound-v2'){throw 'Unsupported explicit fresh setup build policy.'}
    return [ordered]@{
        '$schema'='rusty.manifold.peer.fresh_pair_setup_policy.v2';policy_id='policy.quest.embedded-duplex.fresh-setup.v2';revision=1L;authorization='source_bound'
        initial_status=[ordered]@{'$schema'='rusty.manifold.peer.fresh_pair_setup_initial_status_template.v1';trust_domain='trust.morphospace.peer';roles=@('observer','rendezvous');capability_ids=@('capability.rendezvous.ble','capability.route.rust-direct-p2p','capability.topology.wifi-direct')}
        credential_validity_ms=$(if($null -eq $DevelopmentLifetimePolicy){300000L}else{[long]$DevelopmentLifetimePolicy.delegated_authority_horizon_ms})
    }
}
function Resolve-EmbeddedDuplexFreshSetupBuildSelection {
    param([string]$Selection,[string]$MediaPolicy,$ValidatedProductInputs,[string]$EnrollmentSelectionPath,[string]$ExpectedEnrollmentSelectionSha256,[AllowNull()]$DevelopmentLifetimePolicy)
    $policy=Get-EmbeddedDuplexFreshSetupPolicy $Selection $DevelopmentLifetimePolicy
    if($null-eq$policy){
        if($EnrollmentSelectionPath-or$ExpectedEnrollmentSelectionSha256){throw 'Unselected fresh build cannot carry enrollment target inputs.'}
        return $null
    }
    if($MediaPolicy-cne'ongoing-v2'-or$null-eq$ValidatedProductInputs-or-not$EnrollmentSelectionPath-or$ExpectedEnrollmentSelectionSha256-cnotmatch'^[0-9a-f]{64}$'){
        throw 'Selected fresh build requires ongoing-v2, validated real product inputs and an exact target pin.'
    }
    if($ValidatedProductInputs.closure_sha256-cnotmatch'^[0-9a-f]{64}$'-or$ValidatedProductInputs.manifest_sha256-cnotmatch'^[0-9a-f]{64}$'){throw 'Validated fresh product closure pins absent.'}
    $item=Get-Item -LiteralPath $EnrollmentSelectionPath -ErrorAction Stop
    if($item.PSIsContainer-or($item.Attributes-band[IO.FileAttributes]::ReparsePoint)-ne0){throw 'Fresh target must be an exact regular file.'}
    $bytes=[IO.File]::ReadAllBytes($item.FullName)
    $rawSha=([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))).ToLowerInvariant()
    if($rawSha-cne$ExpectedEnrollmentSelectionSha256){throw 'Fresh target raw pin differs.'}
    $raw=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
    $document=[System.Text.Json.JsonDocument]::Parse($raw)
    try{Assert-FreshSetupNoDuplicateJson $document.RootElement}finally{$document.Dispose()}
    $target=$raw|ConvertFrom-Json -Depth 32 -DateKind String
    Assert-FreshSetupProperties $target @('$schema','operator_id','keys')
    if($target.'$schema'-cne'rusty.manifold.peer.fresh_setup_enrollment_selection.v1'-or$target.operator_id-isnot[string]-or$target.operator_id-cnotmatch'\A[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)*\z'-or$target.keys-isnot[array]-or$target.keys.Count-ne2){throw 'Fresh target selection identity differs.'}
    $routePath=Join-Path $ValidatedProductInputs.root 'route-configuration.json'
    $routeRow=@($ValidatedProductInputs.artifacts|Where-Object path -CEQ 'route-configuration.json')
    if($routeRow.Count-ne1-or(Get-FileHash -LiteralPath $routePath -Algorithm SHA256).Hash.ToLowerInvariant()-cne$routeRow[0].sha256){throw 'Validated product route changed before fresh selection.'}
    $route=Get-Content -Raw -LiteralPath $routePath|ConvertFrom-Json -Depth 32 -DateKind String
    $peers=[string[]]@($route.peers.peer_id);[Array]::Sort($peers,[StringComparer]::Ordinal)
    if($peers.Count-ne2-or$peers[0]-ceq$peers[1]){throw 'Fresh target requires two exact packaged peers.'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for($i=0;$i-lt2;$i++){
        $key=$target.keys[$i]
        Assert-FreshSetupProperties $key @('$schema','peer_id','credential_id','trust_domain','key_id','key_generation','algorithm','public_key_hex','public_key_sha256','key_fingerprint')
        if($key.'$schema'-cne'rusty.manifold.peer.fresh_setup_enrollment_key.v1'-or$key.peer_id-cne$peers[$i]-or$key.credential_id-cne"credential.duplex.$($peers[$i]).1"-or$key.trust_domain-cne'trust.morphospace.peer'-or$key.key_generation-isnot[long]-and$key.key_generation-isnot[int]-or$key.key_generation-ne1-or$key.algorithm-cne'ed25519'-or$key.public_key_hex-cnotmatch'^[0-9a-f]{64}$'){
            throw 'Fresh target key is not the exact typed packaged peer selection.'
        }
        $public=[Convert]::FromHexString($key.public_key_hex)
        $hash=([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($public))).ToLowerInvariant()
        if($key.key_id-cne"ed25519.$hash"-or$key.public_key_sha256-cne"sha256:$hash"-or$key.key_fingerprint-cne"fingerprint.$hash"-or-not$seen.Add($hash)){throw 'Fresh target public key/hash/fingerprint differs.'}
    }
    $canonical=ConvertTo-FreshSetupCanonicalJson $target
    return [pscustomobject]@{
        selection=$Selection;policy=$policy
        policy_sha256=Get-FreshSetupDomainSha256 'rusty.quest.embedded_duplex.fresh_setup_build_policy.v1' $policy
        enrollment_target_raw_sha256=$rawSha
        enrollment_selection_sha256=Get-FreshSetupDomainSha256 'rusty.quest.embedded_duplex.fresh_setup_build_enrollment.v1' $target
        enrollment_selection=$target;enrollment_selection_json=$canonical
        enrollment_selection_base64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($canonical))
        product_input_closure_sha256=[string]$ValidatedProductInputs.closure_sha256
        product_input_manifest_sha256=[string]$ValidatedProductInputs.manifest_sha256
    }
}
function Set-EmbeddedDuplexFreshSetupPolicyDescriptor {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Descriptor,[AllowNull()]$ResolvedSelection)
    if($null-eq$ResolvedSelection){return}
    $Descriptor['embedded_duplex_fresh_setup_policy']=[ordered]@{
        '$schema'='rusty.quest.embedded_duplex.fresh_setup_build_policy.v1';selection=$ResolvedSelection.selection
        policy=$ResolvedSelection.policy;policy_sha256=$ResolvedSelection.policy_sha256
        enrollment_selection_sha256=$ResolvedSelection.enrollment_selection_sha256
        enrollment_target_raw_sha256=$ResolvedSelection.enrollment_target_raw_sha256
        product_input_closure_sha256=$ResolvedSelection.product_input_closure_sha256
        product_input_manifest_sha256=$ResolvedSelection.product_input_manifest_sha256
    }
}
Export-ModuleMember -Function Resolve-EmbeddedDuplexDevelopmentLifetime,Get-EmbeddedDuplexFreshSetupPolicy,Resolve-EmbeddedDuplexFreshSetupBuildSelection,Set-EmbeddedDuplexFreshSetupPolicyDescriptor,ConvertTo-FreshSetupCanonicalJson,Get-FreshSetupDomainSha256
