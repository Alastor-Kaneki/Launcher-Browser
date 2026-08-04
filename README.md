# Launcher Browser

A native Android launcher that is also a multi-tab browser and Shizuku-powered device control surface.

## Current alpha

Version `0.1.0-alpha` includes:

- Android Home-role activity with a wallpaper-visible transparent homepage
- Search/address bar that opens searches and URLs in the built-in browser
- Pinned native apps and a searchable app drawer
- Common ADW/Nova-style icon-pack discovery and `appfilter.xml` icon mapping
- Android widget picking, configuration, hosting, persistence, and removal
- Multi-tab WebView browser in a separate Android process
- Tab restoration, desktop-site mode, downloads, sharing, cookies, and site storage
- Default Home and Browser role requests
- Shizuku/Sui detection and permission flow
- Built-in ADB-shell command console when Shizuku is authorized
- Force-stop actions on home-screen and app-drawer icons
- Quick shell actions for Wi-Fi, battery, memory, packages, and animation scales
- AMOLED, transparency, immersive mode, label, grid, and icon-pack settings

The browser and launcher are separated into different processes so a renderer failure should not take down the home screen.

## Install the CI build

Open the latest successful **Build Android APK** workflow run and download the `Launcher-Browser-0.1.0-alpha-debug` artifact. Debug artifacts are intended for testing only.

## Persistent release signing

Private signing material is intentionally never committed. Configure these repository Actions secrets before running **Signed Release** or pushing a version tag:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The same keystore must be retained for every future release of this repository. The release workflow refuses to generate a release when the persistent signing secrets are absent.

## Shizuku shell

The shell is available only after Shizuku or Sui is running and Launcher Browser has been granted permission. ADB-started Shizuku executes as Android's shell UID; Sui/root-backed Shizuku may execute as UID 0.

Browser pages have no JavaScript bridge to the shell, and downloaded files cannot automatically execute commands.

## Known alpha limitations

- Widgets currently use full-width fixed-height cards; freeform drag and resize are planned.
- Home pages, folders, dock pages, and per-icon swipe actions are planned.
- Common icon packs work, but packs using proprietary formats may not map every icon.
- The shell runs one completed command at a time. A true PTY/rish terminal is planned.
- WebView is used for the initial browser engine. A future engine abstraction can allow GeckoView or another backend.
- Private Space, work-profile containers, notification badges, backup/import, and layout editing are not implemented yet.

## Build

Requirements:

- JDK 17
- Gradle 8.13
- Android SDK 35

```bash
gradle :app:assembleDebug
```

## Package

`dev.alastorkaneki.launcherbrowser`

The debug variant appends `.debug` so it can coexist with a future signed release.
