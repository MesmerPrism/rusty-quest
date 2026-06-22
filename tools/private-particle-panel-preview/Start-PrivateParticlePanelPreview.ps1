param()

$previewPath = Join-Path $PSScriptRoot "index.html"
if (-not (Test-Path -LiteralPath $previewPath)) {
    throw "Preview file not found: $previewPath"
}

Start-Process -FilePath $previewPath
