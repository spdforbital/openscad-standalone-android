#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/android-app"
RUNTIME_ASSET_DIR="$APP_DIR/assets/runtime"

PREFIX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
OPENSCAD_BIN="$PREFIX/bin/openscad"
LIB_ROOT="$PREFIX/lib"
OPENSCAD_SHARE="$PREFIX/share/openscad"
FONTCONFIG_ETC="$PREFIX/etc/fonts"

sanitize_fontconfig() {
  local etc_root="$1"
  local conf="$etc_root/fonts/fonts.conf"
  local readme="$etc_root/fonts/conf.d/README"
  if [ ! -f "$conf" ]; then
    :
  else
    sed -i '/\/data\/data\/com\.termux\/files\/usr\/share\/fonts/d' "$conf"
    sed -i '/\/data\/data\/com\.termux\/files\/usr\/var\/cache\/fontconfig/d' "$conf"
  fi
  if [ -f "$readme" ]; then
    sed -i 's#/data/data/com.termux/files/usr/share/fontconfig/conf.avail#/usr/share/fontconfig/conf.avail#g' "$readme"
  fi
}

if [ ! -f "$OPENSCAD_BIN" ]; then
  echo "OpenSCAD binary not found: $OPENSCAD_BIN" >&2
  exit 1
fi

if [ ! -d "$LIB_ROOT" ]; then
  echo "Library root not found: $LIB_ROOT" >&2
  exit 1
fi

echo "Bundling OpenSCAD runtime into APK assets..."
rm -rf "$RUNTIME_ASSET_DIR"
mkdir -p "$RUNTIME_ASSET_DIR/bin" "$RUNTIME_ASSET_DIR/share" "$RUNTIME_ASSET_DIR/etc" "$RUNTIME_ASSET_DIR/home"

cp "$OPENSCAD_BIN" "$RUNTIME_ASSET_DIR/bin/openscad"
chmod 755 "$RUNTIME_ASSET_DIR/bin/openscad"

python3 "$ROOT_DIR/scripts/collect_runtime_libs.py" \
  "$OPENSCAD_BIN" \
  "$LIB_ROOT" \
  "$RUNTIME_ASSET_DIR/lib"

if [ -d "$OPENSCAD_SHARE" ]; then
  cp -aL "$OPENSCAD_SHARE" "$RUNTIME_ASSET_DIR/share/"
else
  echo "Warning: OpenSCAD share dir missing: $OPENSCAD_SHARE" >&2
fi

if [ -d "$FONTCONFIG_ETC" ]; then
  cp -aL "$FONTCONFIG_ETC" "$RUNTIME_ASSET_DIR/etc/"
  sanitize_fontconfig "$RUNTIME_ASSET_DIR/etc"
else
  echo "Warning: Fontconfig config missing: $FONTCONFIG_ETC" >&2
fi

RUNTIME_SIZE=$(du -sh "$RUNTIME_ASSET_DIR" | cut -f1)
echo "Runtime bundle complete: $RUNTIME_ASSET_DIR ($RUNTIME_SIZE)"
