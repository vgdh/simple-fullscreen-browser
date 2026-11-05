# Simple Fullscreen Browser

A single-activity, chrome-less browser for Android TV / kiosk screens. It shows one
web page fullscreen — no address bar, no tabs, no navigation UI — and exits on **back**.
Built to be launched from **Kodi** (see the companion `plugin.program.android.launcher`
addon), but it runs fine from the app drawer too.

```
Kodi  ──StartAndroidActivity──▶  Fullscreen Web  ──back──▶  Kodi
```

* Package: `com.vitaly.webfullscreen`
* Current version: **2.0-geckoview** (versionCode 6) — minSdk 26, targetSdk 34
* Plain `http://` works. No lock task, no device owner, no kiosk mode, no admin rights.

## Why the rendering engine is bundled

Version 1.x rendered with the device's system WebView. On the target TV that WebView is
permanently stuck at **Chrome 85**, and the WebView component cannot be updated there:
the image ships a vendor-signed provider, so Google-signed WebView builds from Play or
APKMirror fail the signature check, and Play never offers the component for that build
at all.

Chrome 85 predates CSS Cascade Layers (`@layer`, shipped in Chrome 99). Modern dashboard
builds — Homer v24.10.1 and newer, for instance — wrap their **entire** stylesheet in
`@layer`, so Chrome 85 parses the file down to **zero rules**: correct markup, correct
bytes on the wire (200, 784 KB, verified in the server log), completely unstyled page.

Version 2.0 therefore embeds **GeckoView 159** — the Firefox engine — inside the APK.
The engine ships and versions with the app; the device's WebView is not used at all.
The final dex has zero references to `android.webkit.WebView`.

The cost is size (see [size and storage](#size-and-storage)), because the APK carries a
real browser: `libxul.so` and `assets/omni.ja` per ABI.

## Behaviour

* **Start URL** comes from an `EXTRA_URL` extra or a `view` intent; on first run it is
  asked once and stored in `SharedPreferences` (`webfullscreen` / `start_url`), so a
  cold start needs no interaction.
* **Immersive**: system bars hidden and re-hidden on window focus, resume, page load and
  HTML5 fullscreen transitions (video players knock them back out).
* **Desktop engine profile**: `USER_AGENT_MODE_DESKTOP` + `VIEWPORT_MODE_DESKTOP`, so the
  page gets the desktop layout instead of whatever a TV user-agent sniff would produce.
* **`DISPLAY_MODE_FULLSCREEN`**, so viewport units resolve against the whole screen —
  `100vh` equals screen height, which is what kiosk dashboards assume.
* **Back** (remote or keyboard) walks page history first, then finishes back to Kodi.
  `KEYCODE_BACK` is handled directly because some boxes never reach `onBackPressed`.
* **Load errors** show a toast and keep the black background — no browser error page on
  a wall-mounted screen.
* JavaScript on, remote debugging on, `about:config` available, `largeHeap="true"`.

## Build

Requirements: JDK 17+, Android SDK with **platform 37.2** and **build-tools 37.0.0**,
Gradle 9.x.

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh                                # universal APK (arm64-v8a + armeabi-v7a)
./build.sh assembleDebug
```

or directly:

```bash
gradle assembleRelease                       # universal, both ABIs
gradle -Pabis=arm64-v8a assembleRelease      # single ABI, roughly half the size
```

Output: `build/outputs/apk/release/fullscreen-web-release.apk`.

Traps worth knowing if you rebuild this:

* `settings.gradle` adds `https://maven.mozilla.org/maven2/` — GeckoView is published
  there, not on Maven Central.
* Use the **omni** artifact `org.mozilla.geckoview:geckoview`. The per-ABI artifacts
  cannot be combined: both declare the capability `org.mozilla.geckoview:geckoview` and
  Gradle fails resolution with a capability conflict.
* `compileSdk 37` **plus** `compileSdkMinor 2`. `compileSdk 37` alone fails with
  "AAR metadata requires compileSdk 37.2".
* The manifest must **not** contain `<uses-sdk>`: AGP 9 rejects it; SDK levels come from
  the DSL.
* The source tree is hand-rolled (`AndroidManifest.xml`, `src/`, `res/` at the repo root,
  no `app/` module) and `build.gradle` maps it through `sourceSets`.

## Signing

Release builds are signed with `keystore/debug.keystore` (alias `debug`) — the same key
as every 1.x release. That is deliberate: same package plus same certificate means the
APK installs **over** an existing installation, so the stored start URL and the Kodi
launcher shortcut survive the upgrade. A new key would force an uninstall.

## Install on the TV

The target device has no wireless debugging and is not reachable over `adb`, so:

1. Copy the APK from the file share (`\\192.168.200.11\torrent-data`) to a USB stick.
2. Install it from a file manager on the TV (allow "install unknown apps" for it), or
   download it on the TV itself and tap it.
3. Launch once and confirm the page renders. Kodi starts the app by package name, so no
   shortcut needs to be recreated.

The 1.x APK installs over 2.0 the same way, in reverse, if you need to roll back.

## Size and storage

| Variant | APK | Installed footprint (approx.) |
| --- | --- | --- |
| universal (arm64-v8a + armeabi-v7a) | 161 MB | ~320 MB |
| arm64-v8a only | 92 MB | ~180 MB |

Native libraries are extracted on install (`extractNativeLibs=true`), so the installed
footprint is roughly twice the APK size. On a box with little free space use the
arm64-only build — but it installs only on a 64-bit device and is rejected on a 32-bit
one.

## Known limits

* **Audible autoplay is blocked** until the page has had one user interaction. GeckoView
  has no equivalent of WebView's `setMediaPlaybackRequiresUserGesture(false)`; muted
  autoplay works. An unattended dashboard that plays sound needs one remote key press.
* **Rendering differs from Chrome** by design — different engine, different font
  rasterisation. That is the price of an engine that still gets updates.
* **No in-app UI whatsoever** — no zoom, no reload, no address entry after first run.
  To change the start URL, clear app data or send a new intent.
* **Not verified on the target device.** Build, packaging, manifest merge, signing and
  dex contents are verified on the build host. Runtime behaviour could not be tested
  there: no KVM for an emulator, no `adb` to the TV.

## Layout

```
AndroidManifest.xml          single activity, launcher + VIEW intent filters
build.gradle                 AGP 9, GeckoView, abiFilters, signing
settings.gradle              repositories incl. maven.mozilla.org
gradle.properties            AndroidX, JVM args
build.sh                     thin Gradle wrapper
src/com/vitaly/webfullscreen/MainActivity.java
res/                         strings, colors, launcher icon
keystore/debug.keystore      the 1.x signing key — do not regenerate
dist/                        built APKs for sideloading
tools/                       helper scripts
```

## License

See [LICENSE](LICENSE).
