param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $RepoRoot).Path
$output=Join-Path $root ('local-artifacts/native-camera-permission-check/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$cameraPermissions=@('android.permission.CAMERA','horizonos.permission.HEADSET_CAMERA')
$rolePermission='horizonos.permission.SPATIAL_CAMERA'
foreach($withRole in @($false,$true)) {
    $name=if($withRole){'explicit_role'}else{'camera2'}
    $declared=@('org.khronos.openxr.permission.OPENXR','org.khronos.openxr.permission.OPENXR_SYSTEM')+$cameraPermissions
    if($withRole){$declared+= $rolePermission}
    $spec=@{
        schema='rusty.quest.native_app_build.v1';app_id="camera_permission_$name";owner_repo='rusty-quest';package_policy='app-specific-package'
        package_name="io.github.mesmerprism.rustyquest.native_renderer.camera_permission_$name"
        requested_features=@('camera.hwb');denied_features=@();payloads=@()
        permission_allowlist=if($withRole){@($rolePermission)}else{@()}
        declared_manifest=@{permissions=$declared;uses_features=@('android.opengl.gles.3.1','android.hardware.vr.headtracking','android.hardware.camera','android.hardware.camera2.full');activities=@('android.app.NativeActivity');services=@()}
        expected_render_mode='custom-stereo-projection';settings_assertions=@{required_values=@{};required_disabled_modules=@();required_modules=@('camera/hwb');forbidden_modules=@()}
        expected_markers=@{required=@();forbidden=@()};validation_tier='quick'
    }
    $specPath=Join-Path $output "$name.app.json"
    $resultPath=Join-Path $output "$name.resolution.json"
    $spec | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $specPath
    & pwsh -NoProfile -File (Join-Path $root 'tools/Resolve-NativeAppBuild.ps1') -AppSpec $specPath -OutputRoot $output -ResultJsonPath $resultPath -DryRun
    if($LASTEXITCODE -ne 0){throw "Camera permission resolution failed: $name"}
    $result=Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    $lock=Get-Content -Raw -LiteralPath $result.feature_lock_path | ConvertFrom-Json
    $plan=$lock.permission_pregrant
    if((@($plan.runtime_dangerous_permissions | Sort-Object) -join ',') -ne (@($cameraPermissions | Sort-Object) -join ',')){throw 'Camera2 must retain both genuine runtime grant requirements'}
    if(-not $plan.required_before_first_launch){throw 'Camera2 permission guard must remain required'}
    $manifestPath=Join-Path (Split-Path $result.feature_lock_path) 'AndroidManifest.xml'
    $manifest=Get-Content -Raw -LiteralPath $manifestPath
    if($withRole) {
        if(@($plan.role_managed_permissions) -notcontains $rolePermission -or @($plan.declared_permissions) -notcontains $rolePermission -or $manifest -notmatch [regex]::Escape($rolePermission)){throw 'Explicit role-managed declaration was lost'}
    } elseif(@($plan.declared_permissions) -contains $rolePermission -or @($plan.role_managed_permissions).Count -ne 0 -or $manifest -match [regex]::Escape($rolePermission)) {
        throw 'Native Camera2 introduced an unrelated role permission'
    }
}
Write-Output 'Native Camera2 permission resolution passed: required camera grants and explicit role declaration preserved.'
