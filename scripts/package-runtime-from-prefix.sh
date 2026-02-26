#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
ABI="${1:-armeabi-v7a}"
OUT_DIR="${2:-$ROOT_DIR/release-assets}"

OPENSCAD_BIN="$PREFIX/bin/openscad"
LIB_ROOT="$PREFIX/lib"
OPENSCAD_SHARE="$PREFIX/share/openscad"
FONTCONFIG_ETC="$PREFIX/etc/fonts"

sanitize_fontconfig() {
  local etc_root="$1"
  local conf="$etc_root/fonts/fonts.conf"
  local readme="$etc_root/fonts/conf.d/README"
  if [ -f "$conf" ]; then
    sed -i '/\/data\/data\/com\.termux\/files\/usr\/share\/fonts/d' "$conf"
    sed -i '/\/data\/data\/com\.termux\/files\/usr\/var\/cache\/fontconfig/d' "$conf"
  fi
  if [ -f "$readme" ]; then
    sed -i 's#/data/data/com.termux/files/usr/share/fontconfig/conf.avail#/usr/share/fontconfig/conf.avail#g' "$readme"
  fi
}

if [ ! -x "$OPENSCAD_BIN" ]; then
  echo "OpenSCAD binary not found or not executable: $OPENSCAD_BIN" >&2
  exit 1
fi
if [ ! -d "$LIB_ROOT" ]; then
  echo "Library root not found: $LIB_ROOT" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
STAGE="$OUT_DIR/runtime-$ABI-stage"
OUT_ZIP="$OUT_DIR/openscad-runtime-$ABI.zip"
rm -rf "$STAGE"
mkdir -p "$STAGE/runtime/bin" "$STAGE/runtime/share" "$STAGE/runtime/etc" "$STAGE/runtime/home"

cp "$OPENSCAD_BIN" "$STAGE/runtime/bin/openscad"
chmod 755 "$STAGE/runtime/bin/openscad"

python3 "$ROOT_DIR/scripts/collect_runtime_libs.py" \
  "$OPENSCAD_BIN" \
  "$LIB_ROOT" \
  "$STAGE/runtime/lib"

if [ -d "$OPENSCAD_SHARE" ]; then
  cp -aL "$OPENSCAD_SHARE" "$STAGE/runtime/share/"
else
  echo "Warning: OpenSCAD share dir missing: $OPENSCAD_SHARE" >&2
fi

if [ -d "$FONTCONFIG_ETC" ]; then
  cp -aL "$FONTCONFIG_ETC" "$STAGE/runtime/etc/"
  sanitize_fontconfig "$STAGE/runtime/etc"
else
  echo "Warning: Fontconfig config missing: $FONTCONFIG_ETC" >&2
fi

rm -f "$OUT_ZIP"
(
  cd "$STAGE"
  zip -r "$OUT_ZIP" runtime >/dev/null
)

echo "Built runtime bundle: $OUT_ZIP"
ls -lh "$OUT_ZIP"
