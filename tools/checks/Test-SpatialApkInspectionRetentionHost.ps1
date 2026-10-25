param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
Import-Module (Join-Path $repo 'tools/lib/SpatialSourceVersion.psm1') -Force
Import-Module (Join-Path $repo 'tools/EmbeddedDuplexMediaPolicy.psm1') -Force
Import-Module (Join-Path $repo 'tools/EmbeddedDuplexFreshSetupPolicy.psm1') -Force
$copyModule = Join-Path $repo 'tools/lib/SpatialApkInspection.psm1'
if (Test-Path -LiteralPath $copyModule) { Import-Module $copyModule -Force }
$builderPath = Join-Path $repo 'tools/Build-SpatialCameraPanelAndroid.ps1'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($builderPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Builder parser failed.' }
foreach ($name in @('Copy-FileIfChanged','Get-FileSha256','Set-TextFileIfChanged','Get-ZipEntrySha256','Get-NativeReceiptArtifactMetadata')) {
    $fn = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $false))
    if ($fn.Count -ne 1) { throw "Missing actual builder function: $name" }
    . ([scriptblock]::Create($fn[0].Extent.Text))
}
$builder = [IO.File]::ReadAllText($builderPath)
$start = $builder.IndexOf('$apkInspectionRoot = Join-Path $BuildCacheRoot "apk-v"', [StringComparison]::Ordinal)
$end = $builder.IndexOf('$phaseReceipts.Add([ordered]@{', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -lt $start) { throw 'Actual production inspection section not found.' }
# Execute the complete current production section, including tools, ZIP checks,
# JSON publication and copy finalization. Only external SDK tool leaves are modeled.
$inspectionBody = [scriptblock]::Create($builder.Substring($start, $end - $start))
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('rq-apk-retention-' + [Guid]::NewGuid().ToString('N'))
$finished = $false
$oldLog = $env:RQ_INSPECTION_TOOL_LOG
$oldFail = $env:RQ_INSPECTION_FAIL
$oldMutate = $env:RQ_INSPECTION_MUTATE
function Check([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $toolBody = @'
$name = [IO.Path]::GetFileNameWithoutExtension($PSCommandPath)
[IO.File]::AppendAllText($env:RQ_INSPECTION_TOOL_LOG, "$name`t$($args -join '|')`n")
if ($env:RQ_INSPECTION_FAIL -ceq $name) { exit 19 }
switch ($name) {
    'signer' { 'Signer #1 certificate SHA-256 digest: ' + ('a' * 64) }
    'align' { }
    'aapt' {
        if ($env:RQ_INSPECTION_MUTATE -ceq 'copy') { [IO.File]::AppendAllText($args[-1], 'mutated copy') }
        elseif ($env:RQ_INSPECTION_MUTATE) { [IO.File]::AppendAllText($env:RQ_INSPECTION_MUTATE, 'mutated canonical') }
        "package: name='io.example.inspection' versionCode='1' versionName='0.1.0'"
        "sdkVersion:'34'"; "targetSdkVersion:'34'"; "launchable-activity: name='io.example.inspection.Main'"
    }
    'elf' { if ($args[0] -ceq '-lW') { '  LOAD 0x0 0x0 0x0 0x1 0x1 R E 0x4000' } }
}
exit 0
'@
    foreach ($tool in @('signer','align','aapt','elf')) { [IO.File]::WriteAllText((Join-Path $scratch "$tool.ps1"), $toolBody) }
    $apksigner = Join-Path $scratch 'signer.ps1'; $zipalign = Join-Path $scratch 'align.ps1'
    $shortAapt2 = Join-Path $scratch 'aapt.ps1'; $llvmReadelf = Join-Path $scratch 'elf.ps1'
    $BuildCacheRoot = Join-Path $scratch 'cache'
    New-Item -ItemType Directory -Path (Join-Path $BuildCacheRoot 'apk-v') -Force | Out-Null
    $legacy = Join-Path $BuildCacheRoot ('apk-v/' + ('b' * 64) + '.apk')
    [IO.File]::WriteAllText($legacy, 'retained existing milestone')
    $warm = Join-Path $BuildCacheRoot 'c/native-state'; New-Item -ItemType Directory -Path (Split-Path $warm) | Out-Null
    [IO.File]::WriteAllText($warm, 'warm state')
    $legacyHash = Get-FileSha256 $legacy; $warmHash = Get-FileSha256 $warm
    $git = (Get-Command git -CommandType Application | Select-Object -First 1).Source
    $sourceFixture = Join-Path $scratch 'source'
    & $git init --quiet $sourceFixture
    [IO.File]::WriteAllText((Join-Path $sourceFixture 'input.txt'), 'host-only synthetic source fixture')
    & $git -C $sourceFixture add input.txt
    & $git -C $sourceFixture -c user.name=Fixture -c user.email=fixture@example.invalid commit --quiet -m 'Fixture source'
    if ($LASTEXITCODE) { throw 'Fixture Git commit failed.' }
    $commit = (& $git -C $sourceFixture rev-parse HEAD).Trim(); $tree = (& $git -C $sourceFixture rev-parse 'HEAD^{tree}').Trim()
    $results = [Collections.Generic.List[object]]::new()
    foreach ($case in @('success','signer','align','aapt','wrong-signer','receipt-write','canonical-drift','copy-drift','success-after-failures')) {
        $OutDir = Join-Path $scratch $case; New-Item -ItemType Directory -Path $OutDir | Out-Null
        $apkOut = Join-Path $OutDir 'canonical.apk'
        $zip = [IO.Compression.ZipFile]::Open($apkOut, [IO.Compression.ZipArchiveMode]::Create)
        try { $entry = $zip.CreateEntry('lib/arm64-v8a/libreceipt.so'); $stream = $entry.Open(); try { $stream.WriteByte(1) } finally { $stream.Dispose() } } finally { $zip.Dispose() }
        $sha256 = Get-FileSha256 $apkOut
        $env:RQ_INSPECTION_TOOL_LOG = Join-Path $OutDir 'tools.log'
        $env:RQ_INSPECTION_FAIL = if ($case -cin @('signer','align','aapt')) { $case } else { '' }
        $env:RQ_INSPECTION_MUTATE = if ($case -ceq 'canonical-drift') { $apkOut } elseif ($case -ceq 'copy-drift') { 'copy' } else { '' }
        $normalizedExpectedSignerSha256 = if ($case -ceq 'wrong-signer') { 'c' * 64 } else { 'a' * 64 }
        $resolvedAppId = 'io.example.inspection'; $sourceVersionMetadata = New-SpatialSourceVersionMetadata -SourceCompositionFingerprint ('d' * 64)
        $androidComponentClosure = $null; $androidJvmCompilation = $null
        $EmbeddedDuplexMediaPolicy = 'finite'; $freshBuildSelection = $null
        $nativeReceiptJniLib = Join-Path $OutDir 'libreceipt.so'; [IO.File]::WriteAllText($nativeReceiptJniLib, 'modeled ELF tool input')
        $nativeReceiptSha256 = Get-FileSha256 $nativeReceiptJniLib
        $nativeReceiptApkEntry = 'lib/arm64-v8a/libreceipt.so'; $embeddedDuplexProductInputs = $null
        $EmbeddedDuplexSetupHorizonMs = 300000; $UseSourceCompositionVersionName = $false
        if ($case -ceq 'receipt-write') { New-Item -ItemType Directory -Path (Join-Path $OutDir 'apk-inspection.json') | Out-Null }
        $before = @(Get-ChildItem -LiteralPath (Join-Path $BuildCacheRoot 'apk-v') -File | ForEach-Object FullName)
        $caught = $null
        try { . $inspectionBody } catch { $caught = $_ }
        $newCopies = @(Get-ChildItem -LiteralPath (Join-Path $BuildCacheRoot 'apk-v') -File | Where-Object FullName -CNotIn $before)
        if ($case -clike 'success*') {
            if ($caught) { throw $caught }
            Check ($newCopies.Count -eq 0) 'Successful production inspection leaked an APK copy.'
            Check ((Get-FileSha256 $apkOut) -ceq $sha256) 'Canonical APK changed.'
            $inspection = Get-Content -Raw -LiteralPath (Join-Path $OutDir 'apk-inspection.json') | ConvertFrom-Json
            Check ($inspection.apksigner_verified -and $inspection.zipalign_4_and_16k_verified -and $inspection.native_elf_16k_compatible) 'Actual inspection JSON lost its results.'
            # A real downstream capsule reader must work without any inspection APK.
            function FileRecord([string]$p) { @{path=$p;sha256=(Get-FileSha256 $p)} }
            $manifest = Join-Path $OutDir 'build-manifest.json'
            [IO.File]::WriteAllText($manifest, (@{apk=(FileRecord $apkOut);apk_inspection=(FileRecord $apkInspectionPath)} | ConvertTo-Json -Depth 5))
            $capsule = @{schema='rusty.quest.apk_run_capsule.v1';capsule_id='inspection-host-fixture';app_id='inspection';app_lane='spatial-camera-panel-android';source=@{repository=$sourceFixture;commit=$commit;tree=$tree;tracked_worktree_clean=$true};build_lock=(FileRecord $apkInspectionPath);build_manifest=(FileRecord $manifest);apk=(FileRecord $apkOut);runtime_profile=$null;property_manifest=$null;android=@{package_name=$resolvedAppId;activity='io.example.inspection/.Main'};cleanup=@{policy='always-force-stop-and-restore-exact-property-snapshot';serial_exclusive_mutex=$true;restore_on_failure=$true}}
            $capsulePath = Join-Path $OutDir 'run-capsule.json'; [IO.File]::WriteAllText($capsulePath, ($capsule | ConvertTo-Json -Depth 10))
            & (Join-Path $repo 'tools/Test-ApkRunCapsule.ps1') -CapsulePath $capsulePath -ExpectedLane spatial-camera-panel-android | Out-Null
            [IO.File]::AppendAllText($apkOut, 'drift')
            $denied = $false; try { & (Join-Path $repo 'tools/Test-ApkRunCapsule.ps1') -CapsulePath $capsulePath | Out-Null } catch { $denied = $_.Exception.Message -like 'APK hash mismatch:*' }
            Check $denied 'Real capsule reader accepted canonical APK drift.'
        } else {
            Check ($null -ne $caught) "Expected inspection failure was accepted: $case"
            Check ($newCopies.Count -eq 1) "Failure did not retain exactly its new copy: $case"
            if ($case -cne 'copy-drift') { Check ((Get-FileSha256 $newCopies[0].FullName) -ceq $sha256) "Failure copy changed: $case" }
            if ($case -cin @('copy-drift','canonical-drift')) { Check ($caught.Exception.Message -like 'Inspection APK identity changed;*') 'Mutation did not reach final identity guard.' }
            Check (Test-Path -LiteralPath $apkOut -PathType Leaf) 'Failure removed canonical APK.'
        }
        Check ((Get-FileSha256 $legacy) -ceq $legacyHash -and (Get-FileSha256 $warm) -ceq $warmHash) 'Legacy APK or native warm state changed.'
        $results.Add(@{case=$case;result='pass';error=$(if($caught){$caught.Exception.Message}else{$null})})
    }
    # Direct finalization counterexamples supplement the complete production path:
    # they must never authorize an unrelated existing file or an unclosed receipt.
    $guardApk = Join-Path $scratch 'guard.apk'; [IO.File]::WriteAllText($guardApk, 'guard payload')
    $guardSha = Get-FileSha256 $guardApk
    $guardReceipt = Join-Path $scratch 'guard-receipt.json'
    $goodReceipt = @{schema='rusty.quest.spatial_camera_panel.apk_inspection.v1';apksigner_verified=$true;zipalign_4_and_16k_verified=$true;native_elf_16k_compatible=$true} | ConvertTo-Json
    foreach ($case in @('missing-receipt','receipt-drift','failed-receipt','forged-owner','changed-handle')) {
        [IO.File]::WriteAllText($guardReceipt, $goodReceipt)
        $handle = New-QuestApkInspectionCopy -ApkPath $guardApk -InspectionRoot (Join-Path $BuildCacheRoot 'apk-v') -ExpectedApkSha256 $guardSha
        $ownedPath = $handle.path; $expectedReceiptSha = Get-FileSha256 $guardReceipt
        switch ($case) {
            'missing-receipt' { Remove-Item -LiteralPath $guardReceipt }
            'receipt-drift' { [IO.File]::AppendAllText($guardReceipt, ' ') }
            'failed-receipt' { [IO.File]::WriteAllText($guardReceipt, ($goodReceipt.Replace('true','false'))); $expectedReceiptSha = Get-FileSha256 $guardReceipt }
            'forged-owner' { $handle = [pscustomobject]@{id=$handle.id;path=$legacy} }
            'changed-handle' { $handle.path=$legacy }
        }
        $denied=$false
        try { Complete-QuestApkInspectionCopy -Copy $handle -InspectionReceiptPath $guardReceipt -ExpectedInspectionSha256 $expectedReceiptSha }
        catch { $denied=$true }
        Check ($denied -and (Test-Path -LiteralPath $ownedPath)) "Finalization lost owned failure: $case"
        Check ((Get-FileSha256 $guardApk) -ceq $guardSha -and (Get-FileSha256 $legacy) -ceq $legacyHash) "Finalization altered retained payload: $case"
        $results.Add(@{case=$case;result='pass'})
    }
    if ($IsWindows) {
        $outside = Join-Path $scratch 'outside'; New-Item -ItemType Directory -Path $outside | Out-Null
        $linkedCache = Join-Path $scratch 'linked-cache'; New-Item -ItemType Directory -Path $linkedCache | Out-Null
        $link = Join-Path $linkedCache 'apk-v'; New-Item -ItemType Junction -Path $link -Target $outside | Out-Null
        try {
            $denied=$false;try { $null=New-QuestApkInspectionCopy -ApkPath $guardApk -InspectionRoot $link -ExpectedApkSha256 $guardSha } catch { $denied=$_.Exception.Message -like 'Inspection path must have ordinary*' }
            Check ($denied -and @(Get-ChildItem -LiteralPath $outside).Count -eq 0) 'Linked inspection root wrote outside attempt.'
        } finally { Remove-Item -LiteralPath $link -Force }
        $results.Add(@{case='reparse-root';result='pass'})
    }
    $finished = $true
    @{result='pass';scope='complete production inspection section with modeled SDK tools, real ZIP/JSON and downstream capsule reader; no APK compilation/device';cases=$results} | ConvertTo-Json -Depth 6
} finally {
    $env:RQ_INSPECTION_TOOL_LOG=$oldLog; $env:RQ_INSPECTION_FAIL=$oldFail; $env:RQ_INSPECTION_MUTATE=$oldMutate
    if ($finished) {
        $full = [IO.Path]::GetFullPath($scratch); $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $full.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path $full -Leaf) -notmatch '^rq-apk-retention-[0-9a-f]{32}$') { throw 'Unsafe test cleanup path.' }
        Remove-Item -LiteralPath $full -Recurse -Force
    } else { Write-Warning "Failed host fixture preserved: $scratch" }
}
