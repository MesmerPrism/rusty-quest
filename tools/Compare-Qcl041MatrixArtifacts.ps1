param(
    [Parameter(Mandatory=$true)]
    [string]$OlderCurrentWlanSummaryPath,
    [Parameter(Mandatory=$true)]
    [string]$StrictApDisconnectedSummaryPath,
    [string]$OutPath = ""
)

# Compare-Qcl041MatrixArtifacts emits rusty.quest.qcl100_qcl041_matrix_artifact_comparison.v1.
$ErrorActionPreference = "Stop"

$helperRoot = Join-Path $PSScriptRoot "qcl100_native_projection"
. (Join-Path $helperRoot "Qcl041MatrixGate.ps1")

function Write-Qcl041MatrixArtifactComparisonJson {
    param(
        [Parameter(Mandatory=$true)]
        [object]$Value,
        [Parameter(Mandatory=$true)]
        [string]$Path
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $json = ($Value | ConvertTo-Json -Depth 64) + "`n"
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

$comparison = Get-Qcl100Qcl041MatrixArtifactComparison `
    -OlderCurrentWlanSummaryPath $OlderCurrentWlanSummaryPath `
    -StrictApDisconnectedSummaryPath $StrictApDisconnectedSummaryPath

if (-not [string]::IsNullOrWhiteSpace($OutPath)) {
    Write-Qcl041MatrixArtifactComparisonJson -Value $comparison -Path $OutPath
    Get-Content -Raw -LiteralPath $OutPath
} else {
    $comparison | ConvertTo-Json -Depth 64
}
