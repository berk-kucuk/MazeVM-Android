# Copyright and licence

MazeVM is licensed under the **GNU General Public License, version 2**. The full text
is in [LICENSE](LICENSE).

## Why GPL-2.0

This was not a free choice. The APK ships QEMU binaries, and QEMU is GPL-2.0. Licensing
the app's own code under the same terms keeps the whole artefact coherent: one licence
covers what people receive, and there is no question about which terms apply to which
part of the download.

Apache-2.0 for the app code would also have been defensible, since QEMU runs as a
separate process rather than being linked into the app. If you would rather go that
way, replace [LICENSE](LICENSE) and add a note to [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
explaining that the bundled QEMU binaries remain GPL-2.0 regardless. Do it before the
first public release; changing a licence after other people have contributed means
asking every one of them for permission.

## What this obliges you to do when you share the APK

Give people the source along with the binary. Publishing this repository next to the
release does it. [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) has the detail,
including the two modifications `tools/build-qemu.sh` makes to QEMU.

## What it does not cover

Distribution images and container images are downloaded by the user from the projects'
own servers. MazeVM redistributes none of them; each keeps its own licence.

## Header for source files

Not required, but if you want one:

```
Copyright (C) 2026 MazeVM contributors

This program is free software; you can redistribute it and/or modify it under the
terms of the GNU General Public License version 2 as published by the Free Software
Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.
```
