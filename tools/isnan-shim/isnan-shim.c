/*
 * glibc's __isnan for musl — the one symbol androidx's bundled SQLite JNI library
 * (libsqliteJni.so, built against glibc) needs and musl doesn't export.
 *
 * The daemon embeds a prebuilt copy per architecture (src/main/resources/de/yoxcu/stoandl/natives/)
 * and dlopen()s it RTLD_GLOBAL at startup on musl (SqliteNative.kt), so this file is only needed to
 * rebuild those copies (./build.sh) — or, as a manual fallback, to build one for LD_PRELOAD:
 *
 *     cc -O2 -shared -fPIC -Wl,-soname,libisnan-shim.so -o libisnan-shim.so isnan-shim.c
 *
 * No libc calls, so it links with -nostdlib and loads on any libc.
 */
int __isnan(double x) { return __builtin_isnan(x); }
