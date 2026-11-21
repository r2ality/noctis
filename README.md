# Noctis

Noctis is a lightweight, privacy-first Android browser written almost entirely in Kotlin. It uses the **Android System WebView** already supplied by the device; it does not bundle Chromium or GeckoView.

The project borrows design principles from [Monivo](https://github.com/r2ality/monivo): a small browser, no stored browsing-history database, third-party-cookie restrictions, integrated content filtering, and NoScript-style JavaScript control. No Monivo source files are copied into this project.

## Current project baseline

- Application ID: `dev.noctis.browser`
- Kotlin: Android Gradle Plugin 9.4.0 built-in Kotlin support (no separate Kotlin Gradle plugin).
- Android Gradle Plugin: `9.4.0`
- Gradle: `9.6.0`
- `compileSdk`: 36
- `targetSdk`: 36 (Android 16)
- `minSdk`: 26 (Android 8.0; includes WebView Safe Browsing API support)
- Runtime libraries: Android platform APIs only; no AndroidX, Google Play Services, Firebase, analytics, advertising SDK, or account SDK dependencies.

The app should be suitable for Android 16-based LineageOS and PixelOS builds that include a compatible Android System WebView provider. A ROM without a functioning WebView provider cannot render websites until one is installed/enabled.

## Build on NixOS

The `flake.nix` development shell configures two separate Nixpkgs options:

```nix
config = {
  # Android SDK packages are marked unfree by Nixpkgs.
  allowUnfree = true;

  # Explicitly accept the Android SDK license.
  android_sdk.accept_license = true;
};
```

`allowUnfree` is scoped to the package set imported by this flake; it does not
change your system-wide NixOS configuration. Both settings are applied by the
flake, so you should not need `NIXPKGS_ALLOW_UNFREE=1` or `--impure`.

Build a debug APK:

```bash
nix develop
./gradlew assembleDebug
```

The Gradle launcher uses Gradle 9.6.0. It uses a matching `gradle` already on `PATH` or downloads the official Gradle distribution into the user's Gradle cache. `curl` and `unzip` are included in the Nix development shell.

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected device with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the folder in Android Studio and allow Gradle sync to finish. Android Studio should use JDK 17 or newer compatible with AGP 9.4.0.

## Included functionality

- Multi-tab Android WebView browser with back/forward, reload, find-in-page, share, and tab switching.
- Search-or-address bar. Default provider is DuckDuckGo; Brave Search, Startpage, and a SearXNG instance are also supplied.
- Settings UI to select, add, and remove search engines. Custom engine templates must use HTTPS and contain `%s` for the URL-encoded query.
- No browser-owned browsing-history database. WebView's current-tab back/forward list exists only in memory.
- Local bookmarks, stored in app preferences.
- HTTPS-only mode enabled by default. Typed HTTP URLs are upgraded to HTTPS; non-main-frame HTTP resources are rejected, and mixed content is disabled.
- Third-party cookies blocked by default; first-party cookies are also disabled by default and can be enabled separately.
- JavaScript blocked by default on new hosts. Users can allow individual hosts or deliberately enable JavaScript globally.
- Built-in host-based ad/tracker blocklist plus an editable local custom-host list.
- Pop-up windows blocked by default; user-initiated pop-up tabs can be enabled in settings.
- Android WebView Safe Browsing enabled by default when supported by the installed provider.
- Camera, microphone, geolocation, and other WebView permission requests denied by default.
- Private session disables cookies, disables WebView disk cache, and clears cookies/cache/site storage on exit. A persistent marker triggers best-effort cleanup on next launch if the app was force-stopped during a private session.
- Downloads are confirmed before being handed to Android DownloadManager.
- Black circular launcher icon with a monochrome grey sparkle/star.

## Privacy and security notes

Noctis is an early foundation, not a security-audited browser. Important limitations are deliberately documented:

1. **WebView is the browser engine.** Its security patches and supported web features come from the WebView provider installed on the ROM. Keep that provider updated. A de-Googled ROM may ship an AOSP/Chromium WebView provider instead of Google's app; Noctis does not require Google Play Services.
2. **Host filtering is not a full adblock engine.** The included filter is a small hostname list, not a full EasyList parser. `WebViewClient.shouldInterceptRequest` is not a universal network firewall: service workers, WebSockets, some redirects and other browser internals can bypass the callback or expose limited metadata. Use a trusted system-level DNS/VPN filter for broader network coverage.
3. **HTTPS-only is enforced in app navigation callbacks.** Android's manifest permits cleartext traffic so the user's HTTPS-only setting can be changed at runtime. The app upgrades ordinary top-level HTTP navigation and blocks HTTP subresources, but WebView interception cannot promise perfect protection for every possible network path. For environments requiring strict transport enforcement, keep HTTP disabled at the OS/VPN/firewall layer as well.
4. **Private mode is best-effort within WebView.** It blocks cookies and disables the WebView cache, then clears WebView site storage when the session ends. WebView does not expose a general per-tab, fully memory-only profile API to an ordinary app. Cleanup is attempted after an interrupted private session on the next launch; Android can terminate a process without lifecycle callbacks.
5. **Clear on exit is best-effort.** It clears WebView data during a normal activity finish. Android may terminate an app without calling `onDestroy`, so this must not be treated as guaranteed forensic erasure.
6. **Fingerprinting protection is intentionally conservative.** No fake timezone, language, canvas, or user-agent values are injected. Naive spoofing can make a browser more unique. Noctis does not claim Tor Browser-level fingerprint resistance.
7. **Safe Browsing depends on the provider.** It is enabled in WebView settings where supported, but availability and update behavior depend on the installed WebView implementation.
8. **Bookmarks and preferences are persistent local app data.** They are not uploaded by Noctis. Android device backups are disabled in the manifest.

## Project map

```text
app/src/main/java/dev/noctis/browser/
├── MainActivity.kt                 # Native UI, tabs, navigation, settings screens
├── core/BrowserTab.kt              # In-memory tab model
├── privacy/PrivacyWebViewClient.kt # Navigation, TLS and request handling
├── privacy/RequestFilter.kt        # Built-in + user-defined host filtering
└── settings/BrowserSettings.kt     # Local preferences, search engines, bookmarks
```

## Next sensible development steps

- Add instrumentation tests on the actual LineageOS and PixelOS WebView providers.
- Add a proper EasyList-compatible filter parser with a user-controlled list update/import workflow.
- Add a site-permission manager if user-grantable camera/microphone/location support is desired.
- Add a stricter isolated browsing profile using a dedicated WebView process/data-directory strategy, after verifying behavior across supported ROMs.
- Add security regression tests for redirects, HTTP subresources, external URI schemes, downloads, and WebView renderer crashes.

### NixOS: AAPT2 daemon startup failure

If Gradle reports `AAPT2 ... Daemon startup failed` during `:app:processDebugResources`, use the provided `nix develop` shell. The flake sets `GRADLE_OPTS` to use the AAPT2 binary from the Nix-provided Android SDK Build Tools instead of the Maven-downloaded AAPT2 executable, which may not start on NixOS outside an FHS environment. The Build Tools version is pinned to `36.0.0` in both the flake and `app/build.gradle.kts`.

After updating the project, run:

```bash
nix develop
./gradlew --stop
./gradlew clean assembleDebug
```

To verify the selected AAPT2 executable inside the shell:

```bash
test -x "$ANDROID_HOME/build-tools/36.0.0/aapt2"
"$ANDROID_HOME/build-tools/36.0.0/aapt2" version
```

The Kotlin deprecation messages emitted by the compiler are warnings; they are not the cause of the AAPT2 resource-linking failure.
