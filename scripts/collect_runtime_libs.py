#!/usr/bin/env python3
"""Collect shared library dependency closure for OpenSCAD runtime packaging."""

import argparse
import os
import re
import shutil
import subprocess
import sys
from collections import deque

NEEDED_RE = re.compile(r"Shared library: \[(.+?)\]")
SYSTEM_LIBS = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
    "libstdc++.so",
    "libz.so",
}


def run_readelf(path: str) -> str:
    try:
        out = subprocess.check_output(
            ["llvm-readelf", "-d", path],
            stderr=subprocess.STDOUT,
            text=True,
        )
        return out
    except subprocess.CalledProcessError as exc:
        return exc.output or ""


def needed_libs(path: str) -> list[str]:
    return NEEDED_RE.findall(run_readelf(path))


def resolve_lib(lib_name: str, lib_root: str) -> str | None:
    candidate = os.path.join(lib_root, lib_name)
    if os.path.exists(candidate):
        return candidate
    return None


def copy_lib(src: str, dest: str) -> None:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copy2(src, dest)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("binary")
    parser.add_argument("lib_root")
    parser.add_argument("out_dir")
    args = parser.parse_args()

    binary = os.path.abspath(args.binary)
    lib_root = os.path.abspath(args.lib_root)
    out_dir = os.path.abspath(args.out_dir)

    if not os.path.exists(binary):
        print(f"Binary missing: {binary}", file=sys.stderr)
        return 1
    if not os.path.isdir(lib_root):
        print(f"Library root missing: {lib_root}", file=sys.stderr)
        return 1

    queue = deque([binary])
    seen_files = set()
    resolved = {}
    missing = set()

    while queue:
        current = queue.popleft()
        current_real = os.path.realpath(current)
        if current_real in seen_files:
            continue
        seen_files.add(current_real)

        for lib_name in needed_libs(current):
            if lib_name in resolved:
                continue
            src = resolve_lib(lib_name, lib_root)
            if src is None:
                if lib_name not in SYSTEM_LIBS:
                    missing.add(lib_name)
                continue
            resolved[lib_name] = src
            queue.append(src)

    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    for lib_name in sorted(resolved.keys()):
        src = resolved[lib_name]
        dest = os.path.join(out_dir, lib_name)
        copy_lib(os.path.realpath(src), dest)

    print(f"Collected {len(resolved)} libraries into {out_dir}")

    if missing:
        print("Missing non-system libraries:", file=sys.stderr)
        for name in sorted(missing):
            print(f"  - {name}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
