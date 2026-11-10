# HyperSpectrum

An LSPosed module that replaces Xiaomi HyperOS 4's fixed Lottie animation
with six bars driven by the actual audio spectrum.

The bars use artwork colors and settle smoothly when playback pauses. Casting
and other island icons keep their stock appearance.

The settings page shows module status, an audio spectrum switch, artwork color
schemes, and app information. Original Lottie colors are the default, with
optimized original and iOS-style colors also available. Turning the spectrum off
restores the official Lottie animation.

## Compatibility

| Platform | Required components | Status |
| --- | --- | --- |
| Android 17 / HyperOS 4 | LSPosed supporting libxposed API 102; compatible SystemUI and island plugin | Validated on the test device |
| Android 16 / API 36 | The same SystemUI and island plugin interfaces | Minimum SDK; not validated |

Compatibility depends on the ROM's SystemUI and island plugin. Other system
audio may also affect the spectrum.

## Installation

1. Install the module APK.
2. Enable **Miui Island Spectrum** in LSPosed and select only **SystemUI**
   (`com.android.systemui`).
3. Restart the device for initial activation.
4. Open **HyperSpectrum** from the module settings in LSPosed.

## Build

Use a full Git checkout, Android SDK 37, and JDK 25 for the Gradle daemon.
Set `ANDROID_HOME` or specify `sdk.dir` in a local, untracked `local.properties`.

The settings UI uses [miuix](https://github.com/compose-miuix-ui/miuix) Glass
components (0.9.4 development snapshot) published on GitHub Packages, which
requires authentication. Set `gpr.user` and `gpr.key` (a token with
`read:packages`) in `~/.gradle/gradle.properties`, or set `GITHUB_ACTOR` and
`GITHUB_TOKEN`.

To build against a local miuix checkout instead, place it in `../miuix` and
uncomment the composite build in `settings.gradle.kts`. Both builds must use
the same AGP version (currently 9.5.1).

For a signed APK, copy `keystore.properties.example` to `keystore.properties`
and configure your own signing key locally. Keep signing keys and credentials
outside Git.

Build the LSPosed release APK:

Linux/macOS:

```shell
./gradlew :app:assembleRelease
```

Windows:

```powershell
.\gradlew.bat :app:assembleRelease
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`.
Without signing credentials, the output is `app-release-unsigned.apk` in the same
directory.

An optional Zygisk Next backend is available on rooted arm64 devices with
Zygisk Next 1.5.2. Install its ZIP through the root manager and enable the
module. The installer has no ROM-version allowlist; it checks the installation
mode, architecture, and Zygisk Next presence. AudioFlinger compatibility is
checked at runtime. If the native backend is unavailable, Visualizer remains
available and selected by default. See the native
[build and installation instructions](native/spectrum-tap-zn/README.md).

## License

Copyright 2026 wxxsfxyzm. Apache License 2.0. See [LICENSE](LICENSE).
