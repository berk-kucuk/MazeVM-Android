# MazeVM

An Android app that runs real virtual machines with QEMU. Download an ARM64
distribution from the built-in catalog, create a machine, and drive it through an
in-app serial console or a graphical VNC display.

The interface is English by default, with Turkish as the only other language.
Everything is drawn on a true-black OLED surface.

## What it does

- **Bundled QEMU.** `qemu-system-aarch64`, `qemu-system-x86_64` and `qemu-img` ship
  inside the APK. There is no runtime download and no Termux dependency.
- **Distribution catalog that asks the right questions.** Kali, Fedora, Debian, Ubuntu
  Server, Alpine and Arch Linux ARM. Publishers ship several near-identical images per
  release, so nothing downloads straight from a list: picking a distribution opens a
  sheet that asks which release and — for Fedora, which really does publish a disk
  image per desktop — which desktop. Every entry carries a verified URL, a published
  checksum and an honest note about how it boots. Downloads resume, verify and
  decompress on their own.
- **Two ways to see the guest.** A VT100-subset terminal for the serial console, and
  an RFB 3.8 client for the framebuffer, with touch mapped to an absolute pointer and a
  soft keyboard wired to both. Turned sideways, the console drops the app's chrome and
  the system bars and hands the whole panel to the guest.
- **Cloud images that actually log in.** Debian and Ubuntu cloud images ship with no
  password. MazeVM generates a cloud-init `cidata` seed disc so the account you name
  exists on first boot.
- **User-mode networking.** SLIRP, so the guest reaches the internet with no root and
  no privileged setup. Port forwarding is configurable per machine.

## Installing the APK

MazeVM is not on Google Play and is installed by hand.

1. Download `MazeVM-<version>-arm64.apk`.
2. Check it is the file that was published, not something that was altered on the way:

   ```bash
   sha256sum -c MazeVM-<version>-arm64.apk.sha256
   ```

3. Open it. Android will ask permission to install from this source the first time;
   that prompt is normal for anything outside Play.

Every release is signed with the same key, so Android will only accept an update that
came from the same place. If an update refuses to install with a signature error, the
file did not come from this project — do not force it by uninstalling first.

## Requirements

- An **arm64-v8a** device running **Android 9 (API 28)** or newer.
- Free storage to match the image you pick: a Kali install wants roughly 40 GB.
- Patience. Android never grants KVM to an ordinary app, so everything runs under TCG
  software emulation. A first boot takes minutes, not seconds.

## Building

### 1. Build the QEMU runtime

The APK has no QEMU binaries in it until this runs. Expect well over an hour the first
time; afterwards everything under `tools/build` is reused.

```bash
tools/build-qemu.sh
```

It cross-compiles zlib, libffi, pcre2, pixman, glib, libslirp and QEMU with the
Android NDK, then installs them:

| Output | Destination |
| --- | --- |
| `qemu-system-aarch64`, `qemu-system-x86_64`, `qemu-img` | `app/src/main/jniLibs/arm64-v8a/lib*.so` |
| UEFI firmware, ROMs, keymaps | `app/src/main/assets/qemu-data/` |

The `.so` naming is not a mistake. Android will not execute a file out of an app's
data directory; `nativeLibraryDir` is the only app-owned directory that stays
executable, and the package installer only fills it from `jniLibs`. `useLegacyPackaging`
in `app/build.gradle.kts` is what makes the installer unpack them rather than mapping
them out of the APK.

Other entry points:

```bash
tools/build-qemu.sh firmware   # only re-fetch the arm64 UEFI image
tools/build-qemu.sh clean      # throw away build output
```

You need `ninja`, `pkg-config`, `python3`, `curl` and an Android NDK. `meson` is
bootstrapped into a private virtualenv if it is not already installed. The NDK is found
through `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, or the newest one under the SDK.

### 2. Build the app

```bash
./gradlew :app:assembleDebug
```

The app builds and runs without the QEMU binaries — it will just tell you the runtime
is missing instead of starting anything.

### 3. Build a release you can hand to other people

Create a signing key once. Choose your own password; nothing else should ever know it.

```bash
keytool -genkeypair -v -keystore mazevm-release.jks -alias mazevm -keyalg RSA -keysize 4096 -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` and fill in the two
passwords. That file and the `.jks` are both git-ignored, and they need to stay that
way: Android identifies an app by its signing key, so whoever holds this pair can
publish an update that every existing install accepts as genuine. Back it up somewhere
you will still have in a few years — losing it means future releases install as a
separate app and nobody gets an update.

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. It is minified and resource-shrunk,
which takes it from about 77 MB down to 19 MB. CI can supply the same values through
`MAZEVM_STORE_FILE`, `MAZEVM_STORE_PASSWORD`, `MAZEVM_KEY_ALIAS` and
`MAZEVM_KEY_PASSWORD` instead of the file; with neither present the release build still
runs and simply comes out unsigned, which is useful for checking that R8 has not broken
anything.

Verify before publishing:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Only the v3 signature scheme is applied, which is correct here: v3 arrived in Android 9
and `minSdk` is 28, so every device that can install this app can verify it. v1 and v2
exist for older releases this app does not support.

**Before publishing, read [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).** The APK
contains QEMU, which is GPLv2, so giving it to anyone obliges you to give them the
corresponding source as well. Publishing this repository alongside the APK covers it;
handing out the APK on its own does not.

## How it is put together

```
app/src/main/java/com/mazevm/android/
  core/        storage layout, preferences, locale, formatting
  data/        machine store, distribution catalog, image library
  download/    resumable downloads, checksum verification, decompression
  qemu/        runtime discovery, argv construction, QMP, cloud-init seed builder
  vm/          running-machine registry and the foreground service
  terminal/    VT100-subset emulator and its Compose renderer
  vnc/         RFB 3.8 client and its Compose renderer
  ui/          screens, theme, view model
tools/
  build-qemu.sh
  make-icons.sh
```

A few decisions worth knowing about:

**Control and console are separate channels.** QEMU runs with `-serial stdio`, so the
guest console is the process's own pipes. Shutdown, reset and eject go over QMP on a
unix socket instead, which means a guest flooding its console cannot interfere with
control.

**Unix sockets, not ports.** QMP and VNC both listen on unix sockets under the app's
cache directory. Nothing outside the sandbox can reach a running machine, and no port
allocation is needed. `sun_path` is only 108 bytes, so machines get a short token
rather than their full id.

**The cloud-init seed is built by hand.** `CloudInitSeed.kt` writes an ISO 9660 image
with a Joliet tree, because Joliet is what preserves the exact lowercase `user-data`
and `meta-data` names that cloud-init looks for. `CloudInitSeedTest` asserts the
structural invariants; the generated image also opens cleanly in `xorriso` and `7z`.

**Canvas contents are read in the draw phase.** The terminal and the display both read
their revision counter inside the draw block rather than during composition. That is
what subscribes the draw to new frames; reading it only in composition — or not at all
— leaves the canvas recorded once and frozen, which looks exactly like a screenshot of
the first frame rather than like a bug.

**One cleartext exception.** `res/xml/network_security_config.xml` permits plain HTTP
for `archlinuxarm.org` only. That host serves a certificate that does not match its own
name and redirects to HTTP mirrors, so its download is blocked outright otherwise.
Integrity for that image rests on the MD5 in the catalog. Everything else is HTTPS.

**The catalog is data, not code.** `app/src/main/assets/catalog.json` ships in the APK
so the app works offline, and the same document can be re-fetched at runtime from the
URL in `CatalogRepository.REMOTE_URL`. Point that at your own fork to publish a
different image set. It is shaped as distribution → release → edition, which is what
lets the picker ask its questions; `CatalogTest` parses the real file on every build and
checks ids are unique, checksums exist, and nothing but the documented host uses plain
HTTP. Note that Debian's and Alpine's "latest" URLs move, so their published checksums
age out; the app reports a mismatch rather than installing something unverified.

**A tarball is not a bootable image.** Arch Linux ARM publishes a root filesystem, so
creating a machine from it used to produce an empty disk and a UEFI "no bootable
device" prompt. It now builds an install bench instead: the empty target on `/dev/vda`,
the archive exposed raw on `/dev/vdb`, and the Alpine live disc to work from. `tar` and
`gzip` both read a stream and ignore what trails it, so `tar xzf /dev/vdb` unpacks the
archive straight off the block device with no filesystem in between.

## Localisation

`values/strings.xml` is English and `values-tr/strings.xml` is Turkish. That is the
whole set — `resourceConfigurations` in the Gradle file keeps every other locale out
of the APK, and `res/xml/locales_config.xml` limits what the system offers. The
in-app switch goes through `AppCompatDelegate.setApplicationLocales`, which persists
the choice itself.

To change wording, edit both files. To add a language, you would also have to extend
`AppLanguage`, `locales_config.xml` and `resourceConfigurations` — it is deliberately
not a one-file change.

## Limitations

- No hardware acceleration, ever, on an unrooted device. TCG only.
- The graphical display speaks Raw, CopyRect and Hextile encodings. Tight and ZRLE are
  not implemented; over a local unix socket they would not help.
- Arch Linux ARM still needs the partitioning, unpacking and bootloader install done by
  hand from the live system MazeVM attaches. Only the setup is automated, not the
  installation.
- Suspend and resume of a running machine are not wired up yet, though the QMP client
  already has the commands.

## License

Copyright © 2026 Berk Küçük

Released under the GNU General Public License v3.0 — see [LICENSE](LICENSE).
