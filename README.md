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
