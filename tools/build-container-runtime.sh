#!/usr/bin/env bash
#
# Cross-compiles the container runtime for Android arm64.
#
#   app/src/main/jniLibs/arm64-v8a/libproot.so        the userspace chroot
#   app/src/main/jniLibs/arm64-v8a/libproot-loader.so its ELF loader helper
#
# Why proot at all: running a container means running processes with the image's
# filesystem as their root. chroot needs CAP_SYS_ADMIN and user namespaces are closed
# to ordinary Android apps, so the only route left is intercepting syscalls with ptrace
# and rewriting paths, which is what proot does. It costs syscall overhead but the CPU
# runs at full speed, unlike the QEMU backend.
#
# talloc is proot's only external dependency. Upstream builds it with waf, which does
# not cross-compile pleasantly, so the two source files are compiled directly here.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/tools/build-container"
SRC="$ROOT/tools/src"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"

TRIPLE="aarch64-linux-android"
API_LEVEL=28

TALLOC_VERSION=2.4.2

# Termux's fork, not proot-me/proot. Upstream builds for Android but does not work on
# it: the fork carries the Android support that actually matters -- an ashmem/memfd
# extension standing in for the SysV shared memory bionic lacks, plus Android-specific
# handling in execve/ldso.c, path/temp.c, syscall/enter.c and seccomp.c. Building
# upstream produces a binary that starts and then fails every exec with EFAULT.
# This is the same revision Termux itself ships.
PROOT_VERSION=5.1.107.90
PROOT_URL="https://github.com/termux/proot/archive/v$PROOT_VERSION.zip"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m warning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m error:\033[0m %s\n' "$*" >&2; exit 1; }

locate_ndk() {
  for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    [[ -n "$candidate" && -d "$candidate" ]] && { echo "$candidate"; return; }
  done
  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
  local newest
  newest="$(ls -1d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  [[ -n "$newest" ]] || die "no NDK found; set ANDROID_NDK_HOME"
  echo "$newest"
}

setup_toolchain() {
  NDK="$(locate_ndk)"
  local host
  case "$(uname -s)" in
    Linux)  host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) die "unsupported host $(uname -s)" ;;
  esac
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$host"
  export CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  [[ -x "$CC" ]] || die "no compiler at $CC"
  log "compiler $CC"
}

fetch() {
  local url="$1" archive="$2" dir="$3"
  mkdir -p "$SRC"
  [[ -f "$SRC/$archive" ]] || {
    log "downloading $archive"
    curl -fSL --retry 3 -o "$SRC/$archive.part" "$url"
    mv "$SRC/$archive.part" "$SRC/$archive"
  }
  [[ -d "$SRC/$dir" ]] || {
    log "extracting $archive"
    case "$archive" in
      *.zip) ( cd "$SRC" && unzip -q -o "$archive" ) ;;
      *)     tar -C "$SRC" -xf "$SRC/$archive" ;;
    esac
  }
}

build_talloc() {
  [[ -f "$WORK/libtalloc.a" ]] && return 0
  fetch "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz" \
    "talloc-$TALLOC_VERSION.tar.gz" "talloc-$TALLOC_VERSION"

  log "building talloc"
  mkdir -p "$WORK/talloc"
  # Upstream's waf build probes the host libc. Bionic satisfies everything talloc
  # actually needs, so the probe results are supplied directly instead.
  cat > "$WORK/talloc/replace.h" <<'EOF'
#ifndef MAZEVM_REPLACE_H
#define MAZEVM_REPLACE_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#define HAVE_VA_COPY 1
#ifndef va_copy
#define va_copy(d, s) __va_copy(d, s)
#endif
/* Normally pulled in from samba's libreplace, which is not vendored here. */
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif
#endif
EOF

  # waf generates the version macros from the tarball name. Deriving them from the
  # same variable keeps them from drifting when TALLOC_VERSION is bumped.
  local major="${TALLOC_VERSION%%.*}"
  local rest="${TALLOC_VERSION#*.}"
  local minor="${rest%%.*}"
  local release="${rest#*.}"

  "$CC" -c -O2 -fPIC \
    -include "$WORK/talloc/replace.h" \
    -I"$WORK/talloc" -I"$SRC/talloc-$TALLOC_VERSION" \
    -DTALLOC_BUILD_VERSION_MAJOR="$major" \
    -DTALLOC_BUILD_VERSION_MINOR="$minor" \
    -DTALLOC_BUILD_VERSION_RELEASE="$release" \
    -DHAVE_VA_COPY=1 -DHAVE_STDINT_H=1 -DHAVE_STDBOOL_H=1 \
    -DHAVE_STRNLEN=1 -DHAVE_MEMMOVE=1 -DHAVE_VSNPRINTF=1 \
    -DHAVE_CONSTRUCTOR_ATTRIBUTE=1 -DHAVE_DESTRUCTOR_ATTRIBUTE=1 \
    -DHAVE_BUILTIN_EXPECT=1 -DHAVE__EXIT=1 \
    -o "$WORK/talloc.o" "$SRC/talloc-$TALLOC_VERSION/talloc.c"

  "$AR" rcs "$WORK/libtalloc.a" "$WORK/talloc.o"
  log "talloc ok"
}


# glibc declares a few functions from headers bionic keeps elsewhere. These have to be
# fixed per file rather than with a global -include, because proot's loader is compiled
# -ffreestanding and defines its own basename; pulling libgen.h in there collides.
add_include() {
  local file="$1" header="$2"
  grep -q "^#include <$header>" "$file" && return 0
  # Insert after the first include so it lands past the licence header.
  sed -i "0,/^#include /s//#include <$header>\n#include /" "$file"
}

patch_proot() {
  local src="$SRC/proot-$PROOT_VERSION/src"
  log "applying bionic compatibility includes"

  # glibc's headers include each other far more freely than bionic's, so sources that
  # compile on Linux without naming <string.h> or <strings.h> fail here: bzero lives in
  # <strings.h>, and strcmp/memset in <string.h>. Applied as a rule to every
  # translation unit rather than to whichever file happens to fail first, since fixing
  # them one at a time just moves the error to the next file.
  # The loader is excluded: it is -ffreestanding and has no libc headers at all.
  while IFS= read -r file; do
    add_include "$file" strings.h
    add_include "$file" string.h
  done < <(find "$src" -name '*.c' -not -path "$src/loader/*")

  add_include "$src/cli/cli.c" libgen.h  # basename
}

build_proot() {
  fetch "$PROOT_URL" "proot-$PROOT_VERSION.zip" "proot-$PROOT_VERSION"
  patch_proot

  log "building proot"
  local src="$SRC/proot-$PROOT_VERSION/src"
  mkdir -p "$WORK/proot"

  # proot's own Makefile assumes a glibc host and a native build; invoking the
  # compiler directly keeps the cross setup explicit.
  ( cd "$src"
    make -f GNUmakefile clean >/dev/null 2>&1 || true
    # Every tool has to be passed as a make argument, not through the environment:
    # the GNUmakefile assigns CC with `=`, which an environment variable does not
    # override. Getting this wrong silently produces a host x86-64 binary that only
    # the arch check at the end catches.
    #
    # -ltalloc goes in LDFLAGS rather than LDLIBS: the GNUmakefile appends LDFLAGS
    # after the object files and never references LDLIBS, so a library placed there
    # is dropped and every talloc symbol comes out undefined.
    #
    # The flags the GNUmakefile sets for itself are repeated here on purpose. A
    # variable given on the make command line wins over every assignment in the
    # makefile, `+=` included, so passing CPPFLAGS silently discards the makefile's own
    # `-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$(VPATH)`. Losing _FILE_OFFSET_BITS
    # is the dangerous one: it changes the width of off_t and the layout of struct
    # stat, which proot reads and writes directly while translating syscalls, so the
    # result compiles cleanly and then misbehaves at runtime.
    make -f GNUmakefile V=1 proot \
      CC="$CC" \
      LD="$CC" \
      AR="$AR" \
      STRIP="$STRIP" \
      OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" \
      OBJDUMP="$TOOLCHAIN/bin/llvm-objdump" \
      CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$src -I$SRC/talloc-$TALLOC_VERSION -I$WORK/talloc -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\"" \
      CFLAGS="-Wall -Wextra -O2 -fPIC -Wno-error" \
      LDFLAGS="-L$WORK -pie -ltalloc -Wl,-z,noexecstack" 2>&1 | tail -30 )

  [[ -f "$src/proot" ]] || die "proot did not produce a binary"

  mkdir -p "$JNI_DIR"
  cp "$src/proot" "$JNI_DIR/libproot.so"
  "$STRIP" --strip-unneeded "$JNI_DIR/libproot.so" 2>/dev/null || true
  log "installed libproot.so"

  # proot embeds a copy of this same binary inside itself (see the loader-wrapped.o /
  # objcopy step above) and can self-extract it at runtime -- but only into whatever
  # PROOT_TMP_DIR points at, which for this app is cache storage, and Android 10+
  # refuses to execute anything outside nativeLibraryDir regardless of chmod. A
  # self-extracted loader can be written but never run, and the failure surfaces many
  # calls later as an opaque "execve: Bad address" with nothing pointing back at the
  # real cause. Installing this prebuilt copy into jniLibs -- the one location Android
  # still permits execution from -- and having the app set PROOT_LOADER at it is what
  # avoids the self-extraction path entirely.
  #
  # This is the pre-objcopy loader/loader build product, a genuine standalone static
  # ELF; GNUmakefile marks the stripped loader.elf copy .INTERMEDIATE; and Make deletes
  # it once the build finishes, which is why this is taken from loader/loader instead.
  if [[ -f "$src/loader/loader" ]]; then
    cp "$src/loader/loader" "$JNI_DIR/libproot-loader.so"
    "$STRIP" --strip-unneeded "$JNI_DIR/libproot-loader.so" 2>/dev/null || true
    log "installed libproot-loader.so"
  else
    warn "loader/loader was not left behind by the build; containers will not start"
  fi
}

verify() {
  for name in libproot.so libproot-loader.so; do
    local path="$JNI_DIR/$name"
    [[ -f "$path" ]] || die "$name is missing"
    local arch
    arch="$(file -b "$path")"
    case "$arch" in
      *aarch64*) printf '    %-24s ok\n' "$name" ;;
      *) die "$name is not arm64: $arch" ;;
    esac
  done
}

main() {
  case "${1:-all}" in
    clean) rm -rf "$WORK" "$JNI_DIR"/libproot*.so; exit 0 ;;
    all) ;;
    *) die "unknown command: $1" ;;
  esac

  setup_toolchain
  mkdir -p "$WORK" "$SRC"
  build_talloc
  build_proot
  verify
  log "done"
}

main "$@"
