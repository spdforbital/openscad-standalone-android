#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="${1:-$HOME/openscad-src}"
BUILD_DIR="${2:-$HOME/openscad-build-headless}"
OUT_DIR="${3:-$ROOT_DIR/release-assets}"
PREFIX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"

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

if ! command -v cmake >/dev/null 2>&1; then
  echo "cmake is required" >&2
  exit 1
fi
if ! command -v ninja >/dev/null 2>&1; then
  echo "ninja is required" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required" >&2
  exit 1
fi
if ! command -v msgfmt >/dev/null 2>&1; then
  echo "gettext/msgfmt is required" >&2
  exit 1
fi

case "$(uname -m)" in
  aarch64) ABI="arm64-v8a" ;;
  armv7l|armv8l) ABI="armeabi-v7a" ;;
  *)
    echo "Unsupported host architecture for Android runtime packaging: $(uname -m)" >&2
    exit 1
    ;;
esac

if [ ! -d "$SRC_DIR/.git" ]; then
  echo "Cloning OpenSCAD source to $SRC_DIR"
  git clone --depth 1 https://github.com/openscad/openscad.git "$SRC_DIR"
fi

echo "Updating OpenSCAD submodules"
git -C "$SRC_DIR" submodule update --init --recursive

echo "Configuring headless OpenSCAD build"
cmake -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
  -DHEADLESS=ON \
  -DNULLGL=ON \
  -DENABLE_TESTS=OFF \
  -DENABLE_GUI_TESTS=OFF \
  -DUSE_QT6=OFF \
  -DENABLE_CGAL=ON \
  -DENABLE_MANIFOLD=OFF \
  -DUSE_BUILTIN_MANIFOLD=OFF \
  -DUSE_BUILTIN_CLIPPER2=ON \
  -DENABLE_CAIRO=OFF \
  -DCMAKE_REQUIRE_FIND_PACKAGE_Lib3MF=OFF \
  -DCMAKE_BUILD_TYPE=Release

echo "Building OpenSCAD"
cmake --build "$BUILD_DIR" -j"$(nproc)"

BIN="$BUILD_DIR/openscad"
if [ ! -x "$BIN" ]; then
  echo "Missing built binary: $BIN" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
STAGE="$OUT_DIR/runtime-$ABI-stage"
OUT_ZIP="$OUT_DIR/openscad-runtime-$ABI.zip"
rm -rf "$STAGE"
mkdir -p "$STAGE/runtime/bin" "$STAGE/runtime/share" "$STAGE/runtime/etc" "$STAGE/runtime/home"

cp "$BIN" "$STAGE/runtime/bin/openscad"
chmod 755 "$STAGE/runtime/bin/openscad"

python3 "$ROOT_DIR/scripts/collect_runtime_libs.py" \
  "$BIN" \
  "$PREFIX/lib" \
  "$STAGE/runtime/lib"

cp -aL "$PREFIX/share/openscad" "$STAGE/runtime/share/"
cp -aL "$PREFIX/etc/fonts" "$STAGE/runtime/etc/"
sanitize_fontconfig "$STAGE/runtime/etc"

cat > "$STAGE/runtime/BUILD_INFO.txt" <<META
source_repo=https://github.com/openscad/openscad
source_commit=$(git -C "$SRC_DIR" rev-parse HEAD)
source_version=$($BIN --version)
build_type=headless
abi=$ABI
built_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
META

rm -f "$OUT_ZIP"
(
  cd "$STAGE"
  zip -r "$OUT_ZIP" runtime >/dev/null
)

echo "Built runtime bundle: $OUT_ZIP"
ls -lh "$OUT_ZIP"
