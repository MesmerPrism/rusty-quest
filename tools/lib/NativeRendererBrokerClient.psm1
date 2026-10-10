function New-NativeRendererBrokerClientJson {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$TemplateJson,
          [Parameter(Mandatory)][string]$AppId,
          [Parameter(Mandatory)][string]$PackageName,
          [string[]]$SelectedFeatureIds = @())
    $client = $TemplateJson | ConvertFrom-Json
    if ($client.schema -cne 'rusty.quest.broker_client_spec.v1' -or
        $client.client_id -cne 'client.quest.native-renderer' -or
        $client.package_name -cne 'io.github.mesmerprism.rustyquest.native_renderer' -or
        @($client.adapter_permissions).Count -ne 1 -or
        $client.adapter_permissions[0] -cne 'io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION' -or
        @($client.runtime_properties).Count -ne 0 -or @($client.application_defaults).Count -ne 0) {
        throw 'Native renderer broker client lock is not an exact closed signature-scoped binding.'
    }
    if ($AppId -cnotmatch '^(?:[a-z0-9_]+(?:\.[a-z0-9_]+)*|unlocked-development)$' -or
        $PackageName -cnotmatch '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$') {
        throw 'A selected safe app ID and Android package are required.'
    }
    $suffix = $AppId.Replace('_','-').Replace('.','-')
    $client.client_id = "client.quest.native-renderer.$suffix"
    $client.package_name = $PackageName
    $client.feature_lock_id = "lock.broker-client.native-renderer.$suffix.v1"
    $client.marker_namespace = "RUSTY_QUEST_NATIVE_BROKER_CLIENT_$($suffix.ToUpperInvariant().Replace('-','_'))"
    if ($SelectedFeatureIds -ccontains 'ui.experiment_session_hub_status_provider') {
        if ($SelectedFeatureIds -cnotcontains 'ui.breath_composition_control_panel') {
            throw 'Hub status provider requires the selected breath composition panel.'
        }
        $client.capabilities = @(@($client.capabilities) + 'capability.connection_hub.provider.register' | Sort-Object -Unique)
        $client.contract_families = @(@($client.contract_families) + 'rusty.manifold.hub.surface_registration.v1' | Sort-Object -Unique)
    }
    $client | ConvertTo-Json -Depth 16 -Compress
}
Export-ModuleMember -Function New-NativeRendererBrokerClientJson
