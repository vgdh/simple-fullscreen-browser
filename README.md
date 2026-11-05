# Simple Fullscreen Browser

A single-activity, chrome-less browser for Android TV / kiosk screens. It shows one
web page fullscreen — no address bar, no tabs, no navigation UI — and exits on a
double **back**. Built to be launched from **Kodi** (see the companion
`plugin.program.android.launcher` addon), but it runs fine from the app drawer too.

```
Kodi  ──StartAndroidActivity──▶  Fullscreen Web  ──double back──▶  Kodi
```

* Package: `com.vitaly.webfullscreen`
* Current version: **3.8-geckoview** (versionCode 24) — minSdk 26, targetSdk 34
* Plain `http://` works. No lock task, no device owner, no kiosk mode, no admin rights.

Version labels follow the owner's rule: the **major stays 3 and only the minor moves**
(`3.8`, `3.9`, ...). `versionCode` increments on every build, because that is what the
installer compares.

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
  asked once and stored in `SharedPreferences` (`settings` / `start_url`), so a
  cold start needs no interaction.
* **Immersive**: system bars hidden and re-hidden on window focus, resume, page load and
  HTML5 fullscreen transitions (video players knock them back out).
* **Desktop engine profile**: `USER_AGENT_MODE_DESKTOP` + `VIEWPORT_MODE_DESKTOP`, so the
  page gets the desktop layout instead of whatever a TV user-agent sniff would produce.
* **`DISPLAY_MODE_FULLSCREEN`**, so viewport units resolve against the whole screen —
  `100vh` equals screen height, which is what kiosk dashboards assume.
* **Back on a card page reloads the start page.** It does not walk history: a history
  restore can hand back the document without running its JavaScript, or land on the
  error entry a failed navigation left behind. A blank or errored document counts as the
  start page, so BACK still exits while loads are failing.
* **Exit is a double BACK on the start page.** The first press shows "Press BACK again to
  exit", the second within 2.5 s shuts the engine down, removes the task and kills the
  process — so the app is really gone, not minimised, and the app info screen afterwards
  does not offer "Force stop". A 250 ms debounce keeps one physical press from confirming
  its own prompt, because some remotes deliver both `onKeyDown` and `onBackPressed`.
* **`KEYCODE_BACK`** is handled directly, because some boxes never reach `onBackPressed`.
* **Load errors** draw an in-page black panel with the failing address and the real
  Gecko error code and category. GeckoView's own Chrome-style error page reads as a
  broken app on a wall-mounted screen.
* JavaScript on, remote debugging on, `about:config` available, `largeHeap="true"`.

## Reliability

This runs unattended on a TV, so a wedged engine is handled instead of ignored:

* **Two attempts, then close.** A failed load is retried twice, 3 s apart, then the app
  closes itself exactly like a double-BACK exit. Retrying indefinitely only hides the
  fault behind a white screen and holds the remote hostage.
* **`about:blank` counts as a dropped load**, not a healthy page: GeckoView reports
  `page-stop success=true` for it, so success alone proves nothing. It gets two spaced
  re-issues, then one full session rebuild (close, detach, fresh `GeckoSession`).
* **The first load waits for the engine**: `session.isOpen()` *and* the view attached to
  a window, polled every 50 ms for up to 3 s. Loading earlier is silently discarded.
* **Every load re-attaches the session** and sets it active and focused first, because a
  session that is not attached to a view drops `loadUri()` without reporting an error.
* **On-screen log**: a rolling six-line overlay in the top-left shows what the loader is
  doing — page start, page stop, first paint, retries, which BACK branch ran. There is no
  console on a TV, and every fix in 2.x/3.x came out of those six lines, so it stays
  visible on screen.
* **One-shot profile wipe**: a marker in `SharedPreferences` clears Gecko's profile once
  per `WIPE_TAG`. Bump the tag when a change must not be hidden by a cached document — a
  `<script>` tag added to the page never reaches the TV if a service worker still serves
  the old HTML.

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

Build the universal APK unless you know the device is 64-bit: the target TV has a 32-bit
userspace and rejects an `arm64-v8a`-only APK with "app isn't compatible with your
device".

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
* Check the dex after a build that touched `onCreate`: a careless find/replace once
  deleted `setContentView(root)` and the app started to nothing but a black screen.
  `javap -c -p` on `MainActivity` is enough to confirm it is still there.

## Signing

Release builds are signed with `keystore/debug.keystore` (alias `debug`) — the same key
as every 1.x release. That is deliberate: same package plus same certificate means the
APK installs **over** an existing installation, so the stored start URL and the Kodi
launcher shortcut survive the upgrade. A new key would force an uninstall.

## Install on the TV

The target device has no wireless debugging and is not reachable over `adb`, so:

1. Copy the APK from the file share (`\\192.168.200.11\torrent-data\homer-kiosk`) to a
   USB stick.
2. Install it from a file manager on the TV (allow "install unknown apps" for it), or
   download it on the TV itself and tap it.
3. Launch once and confirm the page renders. Kodi starts the app by package name, so no
   shortcut needs to be recreated.

An older APK installs over a newer one the same way, in reverse, if you need to roll back.

## Size and storage

| Variant | APK | Installed footprint (approx.) |
| --- | --- | --- |
| universal (arm64-v8a + armeabi-v7a) | 168 MB | ~330 MB |
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
* **A launch within a few seconds of an exit can land on a white screen.** The killed
  process is still reclaiming the Gecko profile, so the new session's loads are dropped.
  Waiting a few seconds after the exit avoids it. A clock-based settle delay was tried on
  the device and did not help, so it is not in the build.
* **A wedged Gecko content process cannot be revived from inside the app.** Loads,
  retries and a fresh session all do nothing; only a new process recovers. That is why
  the retry ladder ends by closing the app instead of looping.

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
