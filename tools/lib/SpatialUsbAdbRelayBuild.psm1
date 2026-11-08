function Resolve-SpatialUsbAdbRelayBuildSelection {
    [CmdletBinding()]
    param([string]$ResourceDirectory = '', [string]$BuildType = 'Debug',
          [switch]$PublicationBuild, [switch]$EmbeddedProductSelected)
    if ([string]::IsNullOrWhiteSpace($ResourceDirectory)) { return $false }
    $root = Get-Item -LiteralPath $ResourceDirectory -ErrorAction Stop
    if (-not $root.PSIsContainer -or ($root.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'USB relay resource directory must be ordinary'
    }
    $name = 'rusty_quest_embedded_duplex_usb_adb_relay_enabled'
    $declarations = [Collections.Generic.List[object]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $root.FullName -Recurse -File -Filter '*.xml') {
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked USB relay resource refused' }
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
        try {
            $xml = [Xml.XmlDocument]::new()
            $xml.XmlResolver = $null
            $xml.Load($reader)
            foreach ($node in $xml.SelectNodes("//*[@name='$name']")) {
                if ($node.LocalName -cne 'integer' -or $node.NamespaceURI -cne '' -or $node.Attributes.Count -ne 1 -or
                    -not [object]::ReferenceEquals($node.ParentNode, $xml.DocumentElement) -or $xml.DocumentElement.LocalName -cne 'resources' -or
                    $file.Directory.Name -cne 'values' -or $file.Directory.Parent.FullName -ine $root.FullName -or
                    $node.InnerText.Trim() -cnotmatch '^[01]$') {
                    throw 'USB relay capability requires one default integer declaration'
                }
                $declarations.Add([int]$node.InnerText.Trim())
            }
        } finally { $reader.Dispose() }
    }
    if ($declarations.Count -eq 0) { return $false }
    if ($declarations.Count -ne 1) { throw 'Repeated USB relay capability refused' }
    if ($declarations[0] -eq 0) { return $false }
    if ($BuildType -ine 'Debug' -or $PublicationBuild -or -not $EmbeddedProductSelected) {
        throw 'USB relay capability requires an explicitly selected non-publication Debug embedded product'
    }
    return $true
}

function Set-SpatialUsbAdbRelayBuildDescriptor {
    [CmdletBinding()]
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Descriptor, [bool]$Enabled)
    if ($Enabled) {
        $Descriptor['development_usb_adb_relay'] = [ordered]@{
            enabled = $true
            cargo_feature = 'development-usb-adb-relay'
            runtime_profile_selection_required = 'development-usb-adb-relay-v1'
            transport_contract_id = 'rusty.manifold.peer.development_loopback_relay_tcp_transport.v1'
            runtime_authority = $false
        }
    }
}

function Get-SpatialUsbAdbRelayCargoArguments {
    [CmdletBinding()]
    param([bool]$Enabled)
    if ($Enabled) { return @('--features', 'development-usb-adb-relay') }
}

Export-ModuleMember -Function Resolve-SpatialUsbAdbRelayBuildSelection, Set-SpatialUsbAdbRelayBuildDescriptor, Get-SpatialUsbAdbRelayCargoArguments
