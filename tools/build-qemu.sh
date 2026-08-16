#!/usr/bin/env bash
#
# Cross-compiles QEMU and its dependencies for Android arm64-v8a and installs the
# result where the Gradle build expects it.
#
#   app/src/main/jniLibs/arm64-v8a/lib*.so   executables and shared libraries
#   app/src/main/assets/qemu-data/           firmware, ROMs and keymaps
#
# Android refuses to execute a file out of an app's data directory. The one directory
# that stays executable is nativeLibraryDir, which the package installer fills from
# jniLibs -- so every QEMU executable is installed under a "lib<name>.so" name even
# though it is an ELF executable, and app/build.gradle.kts sets useLegacyPackaging so
# the installer actually unpacks them.
#
# Expect the first run to take well over an hour. Later runs reuse everything under
# tools/build.
#
# Usage:
#   tools/build-qemu.sh              build everything that is missing
#   tools/build-qemu.sh clean        remove build output and start over
#   tools/build-qemu.sh firmware     only fetch the UEFI firmware
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/tools/build"
SRC="$ROOT/tools/src"
PREFIX="$WORK/prefix"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSET_DIR="$ROOT/app/src/main/assets/qemu-data"

# Keep these in step with app/build.gradle.kts: ABI must match abiFilters and
# API_LEVEL must match minSdk.
ABI="arm64-v8a"
TRIPLE="aarch64-linux-android"
API_LEVEL=28

JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

ZLIB_VERSION=1.3.1
PIXMAN_VERSION=0.44.2
LIBFFI_VERSION=3.4.6
PCRE2_VERSION=10.44
GLIB_VERSION=2.82.4
SLIRP_VERSION=4.8.0
QEMU_VERSION=9.2.0

# Debian's edk2 package is the least painful source of an arm64 UEFI image; building
# edk2 from source would double the length of this script for an identical blob.
EDK2_DEB_URL="http://deb.debian.org/debian/pool/main/e/edk2/qemu-efi-aarch64_2026.05-2_all.deb"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m warning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m error:\033[0m %s\n' "$*" >&2; exit 1; }

# --------------------------------------------------------------------- toolchain

locate_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
    echo "$ANDROID_NDK_HOME"; return
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "$ANDROID_NDK_ROOT" ]]; then
    echo "$ANDROID_NDK_ROOT"; return
  fi
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
  [[ -d "$TOOLCHAIN" ]] || die "NDK toolchain missing at $TOOLCHAIN"

  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export NM="$TOOLCHAIN/bin/llvm-nm"
  export CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
  export CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"

  [[ -x "$CC" ]] || die "no compiler at $CC (is API $API_LEVEL in this NDK?)"

  # The API level comes from the compiler name, not from a define. Passing
  # -D__ANDROID_API__ as well collides with the NDK's own definition, which QEMU turns
  # into a hard error because it builds with -Werror.
  export CFLAGS="-O2 -fPIC -I$PREFIX/include"
  export CXXFLAGS="$CFLAGS"
  export LDFLAGS="-L$PREFIX/lib"
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
  # Without this, pkg-config prefixes every path with the host sysroot.
  export PKG_CONFIG_SYSROOT_DIR=""

  log "NDK      $NDK"
  log "compiler $CC"
}

require_tools() {
  local missing=()
  for tool in ninja pkg-config python3 curl tar; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
  done
  [[ ${#missing[@]} -eq 0 ]] || die "install these first: ${missing[*]}"

  # meson is the one dependency that is awkward to get at a usable version from a
  # distribution, so it is pinned into a private virtualenv rather than required.
  if ! command -v meson >/dev/null 2>&1; then
    local venv="$WORK/venv"
    if [[ ! -x "$venv/bin/meson" ]]; then
      log "bootstrapping meson into $venv"
      mkdir -p "$WORK"
      python3 -m venv "$venv"
      "$venv/bin/pip" install --quiet --upgrade pip
      # QEMU's configure builds its own nested venv from whichever python is first on
      # PATH, and that step needs distlib and setuptools present in the parent.
      "$venv/bin/pip" install --quiet "meson>=1.4" distlib setuptools
    fi
    export PATH="$venv/bin:$PATH"
  fi
  log "meson    $(meson --version)"
}

# A meson cross file describing the NDK toolchain. Written once and reused by every
# meson-based dependency and by QEMU itself.
write_cross_file() {
  mkdir -p "$WORK"
  cat > "$WORK/android-$ABI.ini" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
ranlib = '$RANLIB'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-O2', '-fPIC', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib']
cpp_args = ['-O2', '-fPIC', '-I$PREFIX/include']
cpp_link_args = ['-L$PREFIX/lib']
prefix = '$PREFIX'

[properties]
# sys_root is deliberately absent. Meson would hand it to pkg-config as
# PKG_CONFIG_SYSROOT_DIR, which then prefixes every -I from our own prefix with the
# NDK sysroot and makes glib.h unfindable. The NDK clang wrapper already knows where
# its sysroot is, so nothing here needs to say so.
pkg_config_libdir = '$PREFIX/lib/pkgconfig'
# glib cannot run its probe binaries when cross-compiling, so the answers bionic
# would give are supplied up front.
growing_stack = false
have_c99_vsnprintf = true
have_c99_snprintf = true
have_unix98_printf = true

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
}

fetch() {
  local url="$1" archive="$2" dir="$3"
  mkdir -p "$SRC"
  if [[ ! -f "$SRC/$archive" ]]; then
    log "downloading $archive"
    curl -fSL --retry 3 -o "$SRC/$archive.part" "$url"
    mv "$SRC/$archive.part" "$SRC/$archive"
  fi
  if [[ ! -d "$SRC/$dir" ]]; then
    log "extracting $archive"
    tar -C "$SRC" -xf "$SRC/$archive"
  fi
}

built() { [[ -f "$PREFIX/.stamp-$1" ]]; }
mark_built() { touch "$PREFIX/.stamp-$1"; }

meson_build() {
  local name="$1" source="$2"; shift 2
  local build="$WORK/$name"
  rm -rf "$build"
  meson setup "$build" "$source" \
    --cross-file "$WORK/android-$ABI.ini" \
    --prefix "$PREFIX" \
    --buildtype release \
    --default-library static \
    "$@"
  ninja -C "$build" -j "$JOBS"
  ninja -C "$build" install
}

# ------------------------------------------------------------------ dependencies

build_zlib() {
  built zlib && return 0
  fetch "https://zlib.net/fossils/zlib-$ZLIB_VERSION.tar.gz" \
    "zlib-$ZLIB_VERSION.tar.gz" "zlib-$ZLIB_VERSION"
  log "building zlib"
  ( cd "$SRC/zlib-$ZLIB_VERSION"
    make distclean >/dev/null 2>&1 || true
    CHOST="$TRIPLE" ./configure --prefix="$PREFIX" --static
    make -j"$JOBS"
    make install )
  mark_built zlib
}

build_libffi() {
  built libffi && return 0
  fetch "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz" \
    "libffi-$LIBFFI_VERSION.tar.gz" "libffi-$LIBFFI_VERSION"
  log "building libffi"
  ( cd "$SRC/libffi-$LIBFFI_VERSION"
    ./configure --host="$TRIPLE" --prefix="$PREFIX" \
      --enable-static --disable-shared --disable-docs
    make -j"$JOBS"
    make install )
  mark_built libffi
}

build_pcre2() {
  built pcre2 && return 0
  fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2_VERSION/pcre2-$PCRE2_VERSION.tar.gz" \
    "pcre2-$PCRE2_VERSION.tar.gz" "pcre2-$PCRE2_VERSION"
  log "building pcre2"
  ( cd "$SRC/pcre2-$PCRE2_VERSION"
    ./configure --host="$TRIPLE" --prefix="$PREFIX" \
      --enable-static --disable-shared --disable-jit --enable-unicode
    make -j"$JOBS"
    make install )
  mark_built pcre2
}

build_pixman() {
  built pixman && return 0
  fetch "https://www.cairographics.org/releases/pixman-$PIXMAN_VERSION.tar.gz" \
    "pixman-$PIXMAN_VERSION.tar.gz" "pixman-$PIXMAN_VERSION"
  log "building pixman"
  meson_build pixman "$SRC/pixman-$PIXMAN_VERSION" \
    -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled
  mark_built pixman
}

build_glib() {
  built glib && return 0
  local minor="${GLIB_VERSION%.*}"
  fetch "https://download.gnome.org/sources/glib/$minor/glib-$GLIB_VERSION.tar.xz" \
    "glib-$GLIB_VERSION.tar.xz" "glib-$GLIB_VERSION"
  log "building glib (the long one)"
  # Bionic has no libmount, no SELinux and no xattr headers, and gettext would drag
  # in a second host build for translations QEMU never shows.
  meson_build glib "$SRC/glib-$GLIB_VERSION" \
    -Dtests=false \
    -Dnls=disabled \
    -Dlibmount=disabled \
    -Dselinux=disabled \
    -Dxattr=false \
    -Dglib_debug=disabled \
    -Dintrospection=disabled \
    -Dman-pages=disabled
  mark_built glib
}

build_slirp() {
  built slirp && return 0
  fetch "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v$SLIRP_VERSION/libslirp-v$SLIRP_VERSION.tar.gz" \
    "libslirp-v$SLIRP_VERSION.tar.gz" "libslirp-v$SLIRP_VERSION"
  log "building libslirp"
  meson_build slirp "$SRC/libslirp-v$SLIRP_VERSION"
  mark_built slirp
}

# ------------------------------------------------------------------------- qemu

# Bionic is close enough to glibc that QEMU builds nearly unmodified, but not quite.
# Each edit here is narrow, idempotent and drops a feature MazeVM never asks for
# rather than papering over a missing libc function.
patch_qemu() {
  local src="$SRC/qemu-$QEMU_VERSION"

  # backends/hostmem-shm.c needs shm_open and shm_unlink, which bionic does not have.
  # It only provides -object memory-backend-shm, which this app never uses.
  local backends="$src/backends/meson.build"
  if grep -q "^  system_ss.add(\[files('hostmem-shm.c'), rt\])" "$backends"; then
    log "dropping memory-backend-shm (bionic has no POSIX shared memory)"
    sed -i "s|^  system_ss.add(\[files('hostmem-shm.c'), rt\])|  # removed for Android: bionic has no shm_open/shm_unlink|" \
      "$backends"
  fi

  # The ivshmem contrib tools need the same missing functions. They are host-side
  # helpers for sharing memory between VMs, which has no meaning on a phone, and
  # --enable-tools is only on so that qemu-img gets built.
  local top="$src/meson.build"
  if grep -q "^have_ivshmem = config_host_data.get('CONFIG_EVENTFD')" "$top"; then
    log "dropping the ivshmem tools (bionic has no POSIX shared memory)"
    sed -i "s|^have_ivshmem = config_host_data.get('CONFIG_EVENTFD')|have_ivshmem = false # Android: bionic has no shm_open/shm_unlink|" \
      "$top"
  fi
}

build_qemu() {
  built qemu && return 0
  fetch "https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz" \
    "qemu-$QEMU_VERSION.tar.xz" "qemu-$QEMU_VERSION"
  patch_qemu

  log "building QEMU $QEMU_VERSION"
  local build="$WORK/qemu"
  rm -rf "$build"; mkdir -p "$build"

  ( cd "$build"
    # QEMU 9 takes the binutils replacements from the environment rather than from
    # flags, so AR/RANLIB/STRIP/NM exported by setup_toolchain are what it uses.
    #
    # Starting from --without-default-features and naming what to switch back on is
    # far more durable than a long --disable list: every feature QEMU adds or renames
    # in a later release defaults to off instead of breaking configure.
    #
    # Bionic has no makecontext/swapcontext, so the ucontext coroutine backend cannot
    # be used; sigaltstack is the fallback QEMU ships for exactly this case.
    "$SRC/qemu-$QEMU_VERSION/configure" \
      --cross-prefix="" \
      --cc="$CC" \
      --cxx="$CXX" \
      --host-cc="cc" \
      --target-list=aarch64-softmmu,x86_64-softmmu \
      --prefix="$PREFIX" \
      --without-default-features \
      --with-coroutine=sigaltstack \
      --enable-system \
      --enable-tcg \
      --enable-slirp \
      --enable-vnc \
      --enable-pixman \
      --enable-tools \
      --enable-fdt=internal \
      --disable-kvm \
      --disable-docs \
      --disable-guest-agent \
      --disable-install-blobs \
      --extra-cflags="$CFLAGS" \
      --extra-ldflags="$LDFLAGS"
    make -j"$JOBS" )

  mark_built qemu
}

# ------------------------------------------------------------------- installation

install_binaries() {
  local build="$WORK/qemu"
  mkdir -p "$JNI_DIR"

  local produced=0
  for binary in \
    "$build/qemu-system-aarch64" \
    "$build/qemu-system-x86_64" \
    "$build/qemu-img"
  do
    [[ -f "$binary" ]] || { warn "missing $(basename "$binary")"; continue; }
    local name; name="$(basename "$binary")"
    log "installing $name -> lib$name.so"
    cp "$binary" "$JNI_DIR/lib$name.so"
    "$STRIP" --strip-unneeded "$JNI_DIR/lib$name.so" 2>/dev/null || true
    produced=$((produced + 1))
  done

  [[ $produced -gt 0 ]] || die "QEMU produced no binaries; check the build log"

  # Anything QEMU links against dynamically has to sit beside it, because
  # LD_LIBRARY_PATH is set to nativeLibraryDir at runtime and nothing else there is
  # searchable. Static dependencies leave this loop with nothing to do.
  if [[ -d "$PREFIX/lib" ]]; then
    shopt -s nullglob
    for lib in "$PREFIX"/lib/*.so*; do
      [[ -f "$lib" ]] || continue
      local base; base="$(basename "$lib")"
      [[ "$base" == lib*.so ]] || continue
      log "installing $base"
      cp "$lib" "$JNI_DIR/$base"
    done
    shopt -u nullglob
  fi
}

install_firmware() {
  local qemu_src="$SRC/qemu-$QEMU_VERSION"
  mkdir -p "$ASSET_DIR"

  if [[ -d "$qemu_src/pc-bios" ]]; then
    log "installing QEMU ROMs and keymaps"
    # Only the blobs an arm64 or x86 guest can actually reach are copied; the full
    # pc-bios tree is mostly firmware for machines this app does not build.
    for blob in \
      efi-virtio.rom efi-e1000.rom efi-eepro100.rom \
      vgabios-stdvga.bin vgabios-virtio.bin vgabios-bochs-display.bin \
      bios-256k.bin vgabios.bin kvmvapic.bin linuxboot_dma.bin
    do
      [[ -f "$qemu_src/pc-bios/$blob" ]] && cp "$qemu_src/pc-bios/$blob" "$ASSET_DIR/"
    done
    [[ -d "$qemu_src/pc-bios/keymaps" ]] && cp -r "$qemu_src/pc-bios/keymaps" "$ASSET_DIR/"
  fi

  fetch_uefi_firmware
}

fetch_uefi_firmware() {
  mkdir -p "$ASSET_DIR"
  if [[ -f "$ASSET_DIR/QEMU_EFI.fd" ]]; then
    log "UEFI firmware already present"
    return 0
  fi

  log "fetching arm64 UEFI firmware"
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  curl -fSL --retry 3 -o "$tmp/edk2.deb" "$EDK2_DEB_URL" || {
    warn "could not download the UEFI firmware"
    warn "put a QEMU_EFI.fd into $ASSET_DIR by hand, or arm64 guests will not boot"
    return 0
  }

  ( cd "$tmp"
    if command -v dpkg-deb >/dev/null 2>&1; then
      dpkg-deb -x edk2.deb extracted
    else
      ar x edk2.deb
      mkdir -p extracted
      tar -C extracted -xf data.tar.* 2>/dev/null
    fi )

  local found
  found="$(find "$tmp/extracted" -name 'QEMU_EFI.fd' -o -name 'AAVMF_CODE.fd' | head -1)"
  if [[ -n "$found" ]]; then
    cp "$found" "$ASSET_DIR/QEMU_EFI.fd"
    log "installed $(basename "$found") as QEMU_EFI.fd ($(du -h "$ASSET_DIR/QEMU_EFI.fd" | cut -f1))"
  else
    warn "no firmware image inside the package; arm64 guests will not boot"
  fi
}

verify() {
  log "verifying the installed binaries"
  local ok=1
  for name in libqemu-system-aarch64.so libqemu-img.so; do
    local path="$JNI_DIR/$name"
    if [[ ! -f "$path" ]]; then
      warn "$name is missing"; ok=0; continue
    fi
    local arch; arch="$(file -b "$path" 2>/dev/null || echo unknown)"
    case "$arch" in
      *aarch64*|*ARM\ aarch64*) printf '    %-32s %s\n' "$name" "ok" ;;
      *) warn "$name is not an arm64 binary: $arch"; ok=0 ;;
    esac
  done
  [[ -f "$ASSET_DIR/QEMU_EFI.fd" ]] \
    && printf '    %-32s %s\n' "QEMU_EFI.fd" "ok" \
    || warn "QEMU_EFI.fd is missing; UEFI guests will not boot"
  [[ $ok -eq 1 ]] || die "verification failed"
}

main() {
  case "${1:-all}" in
    clean)
      log "removing build output"
      rm -rf "$WORK" "$JNI_DIR"/*.so "$ASSET_DIR"
      exit 0
      ;;
    firmware)
      setup_toolchain
      fetch_uefi_firmware
      exit 0
      ;;
    all) ;;
    *) die "unknown command: $1" ;;
  esac

  require_tools
  setup_toolchain
  mkdir -p "$PREFIX" "$SRC" "$WORK"
  write_cross_file

  build_zlib
  build_libffi
  build_pcre2
  build_pixman
  build_glib
  build_slirp
  build_qemu

  install_binaries
  install_firmware
  verify

  log "done. Rebuild the app with: ./gradlew :app:assembleDebug"
}

main "$@"
