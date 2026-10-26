# Closed artifact selection only; omission preserves finite descriptor bytes.
function Get-EmbeddedDuplexMediaPolicy {
    param([AllowNull()][AllowEmptyString()][string]$Selection)
    if ($Selection -ceq 'finite') { return $null }
    if ($Selection -cne 'ongoing-v2') { throw 'Unsupported embedded duplex media policy selection.' }
    return [ordered]@{ '$schema' = 'rusty.manifold.peer.ongoing_media_policy.v2' }
}
function Set-EmbeddedDuplexMediaPolicyDescriptor {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Descriptor,[string]$Selection)
    $policy = Get-EmbeddedDuplexMediaPolicy -Selection $Selection
    if ($null -ne $policy) { $Descriptor['embedded_duplex_media_policy'] = $policy }
}
Export-ModuleMember -Function Get-EmbeddedDuplexMediaPolicy,Set-EmbeddedDuplexMediaPolicyDescriptor
