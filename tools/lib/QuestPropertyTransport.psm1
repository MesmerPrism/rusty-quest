Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-QuestPropertyShellSingleQuoted {
    param([Parameter(Mandatory=$true)][AllowEmptyString()][string]$Value)
    if ($Value.IndexOf([char]0) -ge 0 -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw "Android property shell values must not contain NUL or line breaks."
    }
    $singleQuoteEscape = "'" + [char]34 + "'" + [char]34 + "'"
    return "'" + $Value.Replace("'", $singleQuoteEscape) + "'"
}

function Assert-QuestPropertyEntry {
    param([Parameter(Mandatory=$true)]$Entry)
    $name = [string]$Entry.name
    $value = [string]$Entry.value
    if ([string]::IsNullOrWhiteSpace($name) -or $name.IndexOf([char]0) -ge 0 -or $name.Contains("`r") -or $name.Contains("`n")) {
        throw "Android property name must be non-empty and must not contain NUL or line breaks."
    }
    $null = ConvertTo-QuestPropertyShellSingleQuoted -Value $name
    $null = ConvertTo-QuestPropertyShellSingleQuoted -Value $value
}

function New-QuestPropertySetpropBatches {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)]$Entries,
        [ValidateRange(1, 256)][int]$MaxOperationsPerBatch = 24,
        [ValidateRange(256, 16384)][int]$MaxCommandUtf8Bytes = 3072
    )
    $entriesArray = @($Entries)
    $pending = [Collections.Generic.List[object]]::new()
    $batches = [Collections.Generic.List[object]]::new()
    $pendingBytes = 0
    $batchIndex = 0

    foreach ($entry in $entriesArray) {
        Assert-QuestPropertyEntry -Entry $entry
        $name = [string]$entry.name
        $command = "setprop $(ConvertTo-QuestPropertyShellSingleQuoted -Value $name) $(ConvertTo-QuestPropertyShellSingleQuoted -Value ([string]$entry.value))"
        $commandBytes = [Text.Encoding]::UTF8.GetByteCount($command)
        if ($commandBytes -gt $MaxCommandUtf8Bytes) {
            throw "Android property operation for $name exceeds the bounded shell command size."
        }
        $separatorBytes = if ($pending.Count -eq 0) { 0 } else { [Text.Encoding]::UTF8.GetByteCount('; ') }
        if ($pending.Count -gt 0 -and (($pending.Count -ge $MaxOperationsPerBatch) -or ($pendingBytes + $separatorBytes + $commandBytes -gt $MaxCommandUtf8Bytes))) {
            $batchIndex += 1
            $batchCommand = ($pending | ForEach-Object { [string]$_.command }) -join '; '
            $batches.Add([pscustomobject][ordered]@{
                index = $batchIndex
                operation_count = $pending.Count
                command_utf8_bytes = [Text.Encoding]::UTF8.GetByteCount($batchCommand)
                command = $batchCommand
                entries = @($pending | ForEach-Object { $_.entry })
            })
            $pending.Clear()
            $pendingBytes = 0
            $separatorBytes = 0
        }
        $pending.Add([pscustomobject]@{ command = $command; entry = $entry })
        $pendingBytes += $separatorBytes + $commandBytes
    }
    if ($pending.Count -gt 0) {
        $batchIndex += 1
        $batchCommand = ($pending | ForEach-Object { [string]$_.command }) -join '; '
        $batches.Add([pscustomobject][ordered]@{
            index = $batchIndex
            operation_count = $pending.Count
            command_utf8_bytes = [Text.Encoding]::UTF8.GetByteCount($batchCommand)
            command = $batchCommand
            entries = @($pending | ForEach-Object { $_.entry })
        })
    }
    return @($batches)
}

function Invoke-QuestPropertySetpropBatches {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [string]$AdbServerPort,
        [Parameter(Mandatory=$true)]$Batches
    )
    $base = @()
    if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) { $base += @('-P', $AdbServerPort) }
    $base += @('-s', $Serial)
    $executed = [Collections.Generic.List[object]]::new()
    foreach ($batch in @($Batches)) {
        $output = @(& $Adb @base 'shell' ([string]$batch.command) 2>&1 | ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
        $executed.Add([pscustomobject][ordered]@{
            index = [int]$batch.index
            operation_count = [int]$batch.operation_count
            command_utf8_bytes = [int]$batch.command_utf8_bytes
            exit_code = $exitCode
            output = $output -join "`n"
        })
        if ($exitCode -ne 0) {
            throw "ADB setprop batch $($batch.index) failed with exit code $exitCode."
        }
    }
    return @($executed)
}

function Get-QuestPropertyGlobalReadback {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [string]$AdbServerPort
    )
    $base = @()
    if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) { $base += @('-P', $AdbServerPort) }
    $base += @('-s', $Serial)
    $output = @(& $Adb @base 'shell' 'getprop' 2>&1 | ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) { throw "ADB getprop failed with exit code $LASTEXITCODE." }
    $observed = @{}
    foreach ($line in $output) {
        $match = [regex]::Match($line, '^\[(?<name>[^\]]+)\]: \[(?<value>.*)\]$')
        if (-not $match.Success) { continue }
        $name = $match.Groups['name'].Value
        if ($observed.ContainsKey($name)) { throw "ADB global getprop returned duplicate property: $name" }
        $observed[$name] = $match.Groups['value'].Value
    }
    return [pscustomobject][ordered]@{ observed = $observed; output = $output -join "`n" }
}

function Test-QuestPropertyExactReadback {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)]$Entries, [Parameter(Mandatory=$true)]$Observed)
    $readbacks = [Collections.Generic.List[object]]::new()
    $errors = [Collections.Generic.List[string]]::new()
    $entriesArray = @($Entries)
    $lastIndexByName = @{}
    for ($index = 0; $index -lt $entriesArray.Count; $index += 1) {
        $lastIndexByName[[string]$entriesArray[$index].name] = $index
    }
    for ($index = 0; $index -lt $entriesArray.Count; $index += 1) {
        $entry = $entriesArray[$index]
        $name = [string]$entry.name
        $expected = [string]$entry.value
        $hasValue = $Observed.ContainsKey($name)
        $observedValue = if ($hasValue) { [string]$Observed[$name] } else { '' }
        $status = 'matched'
        $errorText = ''
        if ($lastIndexByName[$name] -ne $index) {
            $status = 'superseded'
        } elseif (-not $hasValue) {
            $status = 'missing'
            $errorText = 'property missing from complete getprop readback'
        } elseif ($observedValue -ne $expected) {
            $status = 'mismatched'
            $errorText = "expected '$expected' observed '$observedValue'"
        }
        if ($status -in @('missing', 'mismatched')) { $errors.Add("${name}: $errorText") }
        $kind = if ($null -ne $entry.PSObject.Properties['kind']) { [string]$entry.kind } else { '' }
        $readbacks.Add([pscustomobject][ordered]@{
            name = $name
            kind = $kind
            expected_value = $expected
            observed_value = $observedValue
            status = $status
            error = $errorText
        })
    }
    return [pscustomobject][ordered]@{ readbacks = @($readbacks); errors = @($errors) }
}

Export-ModuleMember -Function ConvertTo-QuestPropertyShellSingleQuoted, New-QuestPropertySetpropBatches, Invoke-QuestPropertySetpropBatches, Get-QuestPropertyGlobalReadback, Test-QuestPropertyExactReadback
