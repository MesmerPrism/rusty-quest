# Shell recenter adapter

Optional Java library and `app_process` entrypoint for the fixed Quest shell
`recenterView` AppFunction. It requires an actual UID/eUID 2000 shell process,
genuine `com.android.shell` Application/context/attribution and the normally
granted `android.permission.EXECUTE_APP_FUNCTIONS`. The empty library manifest
adds no APK permission, service, initialization or runtime activation.

See [the API, packaging and original-lifetime contract](../../docs/SHELL_RECENTER.md).
The focused host gate compiles the production binding and exercises it with
independent framework doubles, including ordinary APK/permission/firmware
rejection. This is simulated conformance, never device or wearer evidence.
