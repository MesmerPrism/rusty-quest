# Navigator visibility observer

An optional Android Java library for observing the CRCS Navigator visibility
signal. It starts only when its consumer calls `start()`. The empty manifest
declares no permissions, components or automatic initialization.

See [the integration and lifecycle contract](../../docs/NAVIGATOR_VISIBILITY.md).
Run the neutral JVM conformance consumer and SDK source compilation with:

```powershell
pwsh -NoProfile -File tools/Test-NavigatorVisibility.ps1 -AndroidJar <sdk-platform/android.jar>
```

`compile-only/` contains independently authored minimal ABI declarations for the
firmware-specific binding. These declarations are not the vendor implementation
or a permission grant. They are compiled separately and excluded from the AAR.
`host-tests/` contains test doubles and the neutral consumer; none ship in it.
The focused tool produces a minimal AAR under ignored `local-artifacts/` using
the installed JDK, without Gradle, APK packaging, network or device access.
