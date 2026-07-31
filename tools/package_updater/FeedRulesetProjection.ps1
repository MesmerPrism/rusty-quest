Set-StrictMode -Version Latest

function Get-FeedRulesetUtcTimestamp {
    param([object]$Value, [string]$Label)

    try {
        if ($Value -is [DateTime]) {
            $timestamp = [DateTimeOffset]::new(
                ([DateTime]$Value).ToUniversalTime()
            )
        } else {
            $timestamp = [DateTimeOffset]::Parse(
                [string]$Value,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind
            )
        }
    } catch {
        throw "$Label is not a canonical ruleset timestamp."
    }
    $timestamp.UtcDateTime.ToString(
        "o", [Globalization.CultureInfo]::InvariantCulture
    )
}

function Assert-FeedRulesetExactProperties {
    param([object]$Value, [string[]]$Expected, [string]$Label)

    [string[]]$actual = @($Value.PSObject.Properties.Name)
    [string[]]$sortedActual = @($actual)
    [string[]]$sortedExpected = @($Expected)
    [Array]::Sort($sortedActual, [StringComparer]::Ordinal)
    [Array]::Sort($sortedExpected, [StringComparer]::Ordinal)
    if (($sortedActual -join "`n") -cne ($sortedExpected -join "`n")) {
        throw "$Label properties differ from the closed contract."
    }
}

function Assert-PackageUpdateLabsFeedRulesetProjection {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Ruleset,
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, [long]::MaxValue)]
        [long]$ExpectedRulesetId,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedUpdatedAtUtc,
        [Parameter(Mandatory = $true)]
        [string]$ExternallyAuditedFullPolicySha256,
        [Parameter(Mandatory = $true)]
        [ValidateSet("pre-publication", "pre-key-use", "pre-push", "post-push")]
        [string]$Phase
    )

    if ($ExpectedUpdatedAtUtc -cnotmatch
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$" -or
        $ExternallyAuditedFullPolicySha256 -cnotmatch "^[0-9a-f]{64}$") {
        throw "Expected feed-ruleset audit identity is not canonical."
    }
    [string[]]$propertyNames = @($Ruleset.PSObject.Properties.Name)
    foreach ($required in @(
        "id", "name", "target", "source_type", "source", "enforcement",
        "conditions", "rules", "updated_at"
    )) {
        if ($propertyNames -cnotcontains $required) {
            throw "$Phase ruleset projection is missing required field $required."
        }
    }
    if ($propertyNames -ccontains "bypass_actors") {
        throw "$Phase publisher response unexpectedly exposes bypass actors."
    }
    $publisherBypassReport = "not-projected"
    if ($propertyNames -ccontains "current_user_can_bypass" -and
        $Ruleset.current_user_can_bypass -cne "never") {
        throw "$Phase publisher token unexpectedly reports bypass authority."
    }
    if ($propertyNames -ccontains "current_user_can_bypass") {
        $publisherBypassReport = "never"
    }

    Assert-FeedRulesetExactProperties $Ruleset.conditions @("ref_name") `
        "$Phase ruleset conditions"
    Assert-FeedRulesetExactProperties $Ruleset.conditions.ref_name `
        @("exclude", "include") "$Phase ruleset ref condition"
    $include = @($Ruleset.conditions.ref_name.include)
    $exclude = @($Ruleset.conditions.ref_name.exclude)
    $rules = @($Ruleset.rules)
    if ($rules.Count -ne 4) {
        throw "$Phase visible rule closure is not exact."
    }
    foreach ($rule in $rules) {
        Assert-FeedRulesetExactProperties $rule @("type") `
            "$Phase visible rule"
    }
    [string[]]$ruleTypes = @($rules.type | Sort-Object)
    [string[]]$expectedRuleTypes = @(
        "creation", "deletion", "non_fast_forward", "update"
    ) | Sort-Object
    $updatedAtUtc = Get-FeedRulesetUtcTimestamp $Ruleset.updated_at `
        "$Phase ruleset updated_at"
    if ([int64]$Ruleset.id -ne $ExpectedRulesetId -or
        $Ruleset.name -cne "Protect Package Update Labs feed" -or
        $Ruleset.target -cne "branch" -or
        $Ruleset.source_type -cne "Repository" -or
        $Ruleset.source -cne "MesmerPrism/rusty-quest" -or
        $Ruleset.enforcement -cne "active" -or
        $include.Count -ne 1 -or
        $include[0] -cne "refs/heads/package-update-labs-feed" -or
        $exclude.Count -ne 0 -or
        ($ruleTypes -join "`n") -cne ($expectedRuleTypes -join "`n") -or
        $updatedAtUtc -cne $ExpectedUpdatedAtUtc) {
        throw "$Phase visible feed-ruleset projection differs."
    }

    $visiblePolicy = [ordered]@{
        id = [int64]$Ruleset.id
        name = [string]$Ruleset.name
        target = [string]$Ruleset.target
        source_type = [string]$Ruleset.source_type
        source = [string]$Ruleset.source
        enforcement = [string]$Ruleset.enforcement
        include = @($include)
        exclude = @($exclude)
        rule_types = @($ruleTypes)
        updated_at_utc = $updatedAtUtc
        publisher_bypass_report = $publisherBypassReport
    }
    $visibleJson = $visiblePolicy | ConvertTo-Json -Depth 8 -Compress
    $visibleHash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($visibleJson)
        )
    ).ToLowerInvariant()
    [pscustomobject][ordered]@{
        schema = "rusty.quest.package_update_labs_feed_ruleset_projection.v1"
        phase = $Phase
        ruleset_id = [int64]$Ruleset.id
        updated_at_utc = $updatedAtUtc
        visible_projection_sha256 = $visibleHash
        externally_audited_full_policy_sha256 =
            $ExternallyAuditedFullPolicySha256
        bypass_actor_closure = "externally-audited-not-projected"
        publisher_bypass_report = $publisherBypassReport
    }
}
