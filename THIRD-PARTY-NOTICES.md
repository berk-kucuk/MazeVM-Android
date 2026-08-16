# Third-party notices

MazeVM ships compiled copies of other people's software inside its APK. This file
records what they are and what distributing them obliges you to do. It is a factual
summary, not legal advice.

## The obligation that actually bites

**QEMU is licensed under GPL version 2.** The APK contains `qemu-system-aarch64`,
`qemu-system-x86_64` and `qemu-img` as `lib*.so` under `lib/arm64-v8a/`. Handing that
APK to anyone else is distribution, and GPLv2 section 3 requires you to also give every
recipient the *corresponding source* for those binaries — the exact sources plus the
scripts used to configure and build them.

For this project that means publishing, alongside the APK:

- `tools/build-qemu.sh`, which pins every version and records every patch applied.
- The upstream tarballs it downloads, or a durable link to them.
- This file.

Publishing the repository satisfies this, because the build script is the complete
recipe. Handing out only the APK does not.

The same applies to any modification you make to QEMU. `tools/build-qemu.sh` currently
makes two: it removes `backends/hostmem-shm.c` and disables the ivshmem tools, both
because bionic has no `shm_open`. Those edits are in the script rather than in a patch
file, which keeps them visible.

## What is bundled

| Component | Version | License | Where it ends up |
| --- | --- | --- | --- |
| QEMU | 9.2.0 | GPL-2.0-only | `lib/arm64-v8a/libqemu-*.so` |
| GLib | 2.82.4 | LGPL-2.1-or-later | statically linked into QEMU |
| pixman | 0.44.2 | MIT | statically linked into QEMU |
| libslirp | 4.8.0 | BSD-3-Clause | statically linked into QEMU |
| zlib | 1.3.1 | Zlib | statically linked into QEMU |
| libffi | 3.4.6 | MIT | statically linked into QEMU |
| PCRE2 | 10.44 | BSD-3-Clause | statically linked into QEMU |
| edk2 / AAVMF firmware | Debian 2026.05-2 | BSD-2-Clause-Patent | `assets/qemu-data/QEMU_EFI.fd` |
| QEMU ROMs and keymaps | 9.2.0 | GPL-2.0-only and others | `assets/qemu-data/` |

GLib is LGPL and is linked statically. LGPL-2.1 section 6 wants recipients to be able
to relink the result against a modified GLib; publishing `tools/build-qemu.sh` is what
provides that, since it rebuilds the whole chain from source.

### Java and Kotlin dependencies

| Component | License |
| --- | --- |
| Kotlin standard library, coroutines, serialization | Apache-2.0 |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 |
| Apache Commons Compress | Apache-2.0 |
| XZ for Java (org.tukaani) | Public domain (0BSD) |

## What is not bundled

Distribution images — Kali, Fedora, Debian, Ubuntu, Alpine, Arch Linux ARM — are
downloaded by the user at runtime from the projects' own servers. MazeVM redistributes
none of them, and each stays under its own licence and trademark policy. The same holds
for container images pulled from a registry.

Distribution names and logos belong to their projects. MazeVM refers to them to say
what it can download, which is nominative use; it is not affiliated with or endorsed by
any of them.

## Your own code

The `app/` sources are yours and this file says nothing about them. You still have to
choose a licence for them before publishing, or people will have no permission to do
anything with the repository. Given that the APK already ships GPLv2 binaries, GPL-2.0
is the simplest choice that keeps the whole artefact coherent; Apache-2.0 also works if
you would rather the app code stay permissive, since QEMU runs as a separate process
rather than being linked in.
