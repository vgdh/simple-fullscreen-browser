# Simple Fullscreen Browser

A ~90 KB Android app that shows **one web page fullscreen, with no browser UI at
all**, and closes on **back**. Built to be launched from **Kodi** on Android
phones and TV boxes, but it also runs fine from the app drawer.

Plain `http://` works. Nothing is locked: no lock task, no device owner, no kiosk
mode, no admin rights — back always gets you out.

```
Kodi  ──StartAndroidActivity──▶  Fullscreen Web  ──back──▶  Kodi
```

## Why this exists

Filling a screen with one page sounds trivial until you look at the options:

| Option | Why it fails |
|---|---|
| Kiosk / digital-signage apps | Paid, or they lock the device down with lock task, device-owner or COSU |
| PWA / WebAPK | Requires a web app manifest **served over HTTPS** — useless for a LAN page on `http://` |
| Kodi add-ons that "open a browser" | Windows/Linux only; nothing to hand off to on Android |
| Full browsers in fullscreen | Address bar, tabs and gestures come back the moment you touch the screen |

So: a single `Activity` around a `WebView`. No Gradle, no AndroidX, one class,
one dependency — the platform.

## Features

- URL comes from the intent: string extra `url` first, then `ACTION_VIEW` data.
- Fullscreen via immersive-sticky flags, re-applied on focus change. No address
  bar, no toolbar, ever.
- Screen kept awake while visible (`FLAG_KEEP_SCREEN_ON`).
- JavaScript + DOM storage on, `mediaPlaybackRequiresUserGesture=false` so
  autoplaying video works without a tap, fullscreen video supported.
- Back does page history first, then `finish()` — you land straight back in Kodi.
  `KEYCODE_BACK` is handled explicitly too, so D-pad remotes behave like a phone.
- First run with no URL asks for the address once and remembers it.
- A failed load shows a toast with the error instead of a silent black screen.
- Permission: `INTERNET` only. `minSdk 21` (Android 5.0), `targetSdk 34`.

Deliberately **not** included: tabs, bookmarks, zoom, downloads, file pickers,
incognito, ad blocking, settings screen. Every one of those is a step away from
"press a key in Kodi, see the page".

## Install

Grab `fullscreen-web.apk` from [Releases](releases/latest), then either:

```bash
adb install -r fullscreen-web.apk
```

or copy it to the device and open it with a file manager (you will need to allow
"install unknown apps" for that file manager). Some MIUI/HyperOS builds refuse
`adb install` with `INSTALL_FAILED_USER_RESTRICTED` and show no dialog — tapping
the APK in Files works.

## First run

With no address known, the app shows a **Start page** dialog prefilled from
`res/values/strings.xml` → `default_url` (a placeholder, `https://www.google.com/`).
What you confirm is stored in `SharedPreferences` (`settings` / `start_url`) and
reused silently from then on. Bare hosts get `http://` prepended.

Address precedence, first match wins:

1. `url` string extra on the intent
2. `ACTION_VIEW` intent data (`http://…`)
3. the stored value
4. the setup dialog (only if none of the above)

To change the start page: **clear the app's data** (Settings → Apps → Fullscreen
Web → Storage → Clear data), or launch it once from Kodi with the new URL and no
stored value set.

## Launch from Kodi

The app must be installed on the **same device** as Kodi. Add as a favourite, or
use it in a keymap / `.strm` / `playercorefactory.xml`:

```
StartAndroidActivity(com.vitaly.webfullscreen,android.intent.action.VIEW,,http://192.168.1.10:8080/)
```

Parameter order is
`package, action, dataType, dataURI, flags, extras, action, category, className`
(params 5–9 are Kodi 20+ only). `dataType` is empty, hence the `,,`.

Or pass the address as an extra instead of the URI:

```
StartAndroidActivity(com.vitaly.webfullscreen,android.intent.action.MAIN,,,,"url=http://192.168.1.10:8080/")
```

Test it without Kodi, from a PC with adb:

```bash
adb shell am start -a android.intent.action.VIEW \
  -d http://192.168.1.10:8080/ com.vitaly.webfullscreen
```

**Remotes without a back key** (some TV boxes map only `KEYCODE_ESCAPE`): bind it
in Kodi or add a keyboard remap; the app listens for `KEYCODE_BACK` specifically.

## Build from source

Requirements: JDK 17+ and Android SDK **build-tools 34.0.0** + **platform 34**
(`ANDROID_HOME` or `ANDROID_SDK_ROOT` pointing at the SDK).

```bash
ANDROID_HOME=~/Android/Sdk ./build.sh
```

The build is plain SDK tooling, no Gradle: `aapt2 compile` → `aapt2 link` →
`javac` → `d8` → `zip` → `zipalign` → `apksigner`. Output: `fullscreen-web.apk`.

`build.sh` generates `keystore/debug.keystore` on first run (password `android`).
**Keep that file** — it is gitignored on purpose, and the same key is what lets a
new build install *over* the old one. Lose it and you must uninstall first,
wiping the saved start page.

## Repository layout

```
AndroidManifest.xml   package, permissions, activity + intent filters
build.sh              the whole build (bash + SDK tools)
src/com/vitaly/webfullscreen/MainActivity.java   the single activity
res/values/strings.xml      app strings, default_url placeholder
res/mipmap-*/               launcher icon (generated, see below)
tools/make_icons.py         regenerates the icon set
tools/icon-source.png       icon artwork
```

## Icon

`tools/make_icons.py` trims `tools/icon-source.png` to its ink bounding box and
regenerates every density, plus a 108 dp adaptive foreground:

```bash
python3 tools/make_icons.py            # needs Pillow
```

Sizing matters more than it looks. The mark's petals bulge to all four sides, so
its extremes sit on the diagonals: content occupying fraction `f` of the canvas
reaches `f/2*sqrt(2)` from the centre. An adaptive icon only shows a 72 dp circle
inside its 108 dp canvas, so `f` must stay under ≈0.47 or launcher masks shave the
tips. The script uses 0.44 for the adaptive foreground and 0.66 for legacy
bitmaps.

## Notes and limits

- The package id is `com.vitaly.webfullscreen` and stays that way so upgrades
  install in place. Forking? Change it in `AndroidManifest.xml`.
- `usesCleartextTraffic="true"` and the exported `http`/`https` `VIEW` filter mean
  any app can ask it to open a page. It is a viewer with `INTERNET` only — no
  file access, no storage, no injected JS bridges.
- No error page, only a toast; a dead host leaves the previous page or a blank
  screen behind.
- No `shouldOverrideUrlLoading` override, so navigation stays in the same WebView
  and fullscreen survives it.
- `allowBackup="false"`, so the stored URL does not travel to a new device.

## License

MIT — see [LICENSE](LICENSE). The icon artwork in `tools/icon-source.png` is
third-party-looking; it ships here as-is, check its origin before reusing it.
