#!/usr/bin/env bash
# Rebuild the embedded __isnan shims (see isnan-shim.c) for every architecture androidx's bundled
# SQLite ships a Linux JNI library for (x86_64, aarch64), straight into the daemon's resources.
#
# Needs clang + lld only: -nostdlib means no libc, sysroot or cross toolchain, so any host builds
# both. The output is libc-free (no NEEDED entries), ~2 KB, and exports exactly __isnan. Commit the
# two .so files after running this; the Gradle build just packs them into the JAR.
#
# Usage: tools/isnan-shim/build.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../src/main/resources/de/yoxcu/stoandl/natives"

command -v clang >/dev/null || { echo "clang not found" >&2; exit 1; }
command -v ld.lld >/dev/null || { echo "ld.lld not found (install lld)" >&2; exit 1; }

for arch in x86_64 aarch64; do
	dir="$out/linux-$arch"
	mkdir -p "$dir"
	# --hash-style=both: DT_HASH + DT_GNU_HASH, so old and new loaders alike can look the symbol up.
	# --build-id=none, -fno-ident: no build-dependent bytes beyond the linker's .comment.
	clang --target="$arch-linux-gnu" -O2 -shared -fPIC -nostdlib -fno-ident -fuse-ld=lld \
		-Wl,--hash-style=both -Wl,-soname,libisnan-shim.so -Wl,--build-id=none -Wl,-s \
		-o "$dir/libisnan-shim.so" "$here/isnan-shim.c"
	chmod 644 "$dir/libisnan-shim.so"

	if command -v readelf >/dev/null; then
		if readelf -dW "$dir/libisnan-shim.so" | grep -q NEEDED; then
			echo "linux-$arch: unexpected NEEDED entry" >&2; exit 1
		fi
		readelf --dyn-syms -W "$dir/libisnan-shim.so" | grep -qw __isnan \
			|| { echo "linux-$arch: __isnan not exported" >&2; exit 1; }
	fi
	echo "linux-$arch: $(wc -c <"$dir/libisnan-shim.so") bytes  $(sha256sum "$dir/libisnan-shim.so" | cut -c1-16)"
done
