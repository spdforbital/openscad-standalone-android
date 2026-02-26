# OpenSCAD Standalone Android


This is a self-contained APK that:

- Uses a native Android UI (no WebView server dependency)
- Runs OpenSCAD directly inside the app process
- Bundles the OpenSCAD binary, required shared libraries, and OpenSCAD data files into APK assets

## Build

```bash
cd ~/openscad-standalone-android
./build-apk.sh
```

Output:

- `~/openscad-standalone-android/OpenSCAD-Standalone.apk`

## Notes

- Runtime assets are generated at build time 
- The app writes projects to internal app storage.
- STL export writes to app external files under Downloads.
- This specific OpenSCAD build crashes on PNG output, so preview is rendered from STL in a software renderer viewer from java Canvas (opengl viewing is finicky on different devices) instead of PNG images.
- Viewer controls: one-finger rotate, two-finger pan/zoom, plus shaded/wireframe toggle.

## Runtime Downloader / Updater

The app includes a `Runtime` screen in the toolbar that:

- Shows whether a downloaded runtime is installed
- Shows device ABI (`arm64-v8a` / `armeabi-v7a`)
- Checks the latest runtime version from GitHub releases on app boot
- Lets you refresh status and download/update manually

Source repo used by the updater:

- `https://github.com/spdforbital/openscad-standalone-android`

### Release Asset Naming

Upload runtime assets to the latest release with ABI in the filename so the app can auto-match:

- `openscad-runtime-arm64-v8a.zip` (or include `aarch64` / `arm64`)
- `openscad-runtime-armeabi-v7a.zip` (or include `armv7`)

### Build Runtime From Source (Trustless)

You can build your own headless runtime bundle directly from OpenSCAD source:

```bash
cd ~/openscad-standalone-android
./scripts/build-headless-runtime.sh
```

Output:

- `~/openscad-standalone-android/release-assets/openscad-runtime-<abi>.zip`

Notes:

- Build on each target device architecture (for example, run once on `arm64-v8a` device and once on `armeabi-v7a` device) to get matching binaries.
- Runtime execution in the Android app is standalone and does not require the Termux app process.
- The `TERMUX_PREFIX` default in helper scripts is only a build-time default path; the APK/runtime updater do not depend on the Termux app being installed.
- CI option for `armeabi-v7a`: run GitHub Actions workflow `Build ARMv7 Runtime` and upload to a chosen release tag.

Supported payload formats:

- `.zip` archive containing either:
  - `runtime/bin/openscad` (+ optional `lib/share/etc/home`), or
  - `bin/openscad` at archive root (+ optional `lib/share/etc/home`)
- Raw binary file named with ABI tokens (installed as `runtime/bin/openscad`)
