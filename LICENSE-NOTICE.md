# Source, license, and trademark notice

RevisionGraph

Copyright (C) 2026 Noodles Studio and RevisionGraph contributors.

RevisionGraph is distributed under the GNU General Public License as published by the Free
Software Foundation, either version 2 of the License, or (at your option) any later version
(`GPL-2.0-or-later`). The complete license text is provided in [LICENSE](LICENSE).

## TortoiseGit provenance

RevisionGraph is an independent Kotlin/JVM implementation inspired by the user-visible behavior,
data semantics, and graph presentation of
[TortoiseGit RevisionGraph](https://github.com/TortoiseGit/TortoiseGit/tree/master/src/TortoiseProc/RevisionGraph).
TortoiseGit's RevisionGraph source files are licensed under GNU GPL version 2 or, at the recipient's
option, any later version.

This repository does not include TortoiseGit binaries or copy TortoiseGit C++ source files. The
project nevertheless adopts `GPL-2.0-or-later` to preserve the freedoms of the upstream project and
to provide an unambiguous basis for modification and redistribution.

## OGDF

TortoiseGit uses the Open Graph Drawing Framework (OGDF) for its Sugiyama layout pipeline. This
plugin does not link to, bundle, or distribute OGDF. Its ranking, crossing-reduction, coordinate,
and routing code is a separate pure-Kotlin implementation. No OGDF native library, source file,
JNI binding, or JNA binding is included in the plugin distribution.

## Third-party platforms and trademarks

RevisionGraph integrates with public IntelliJ Platform and bundled Git4Idea APIs. JetBrains,
IntelliJ IDEA, and their associated marks are trademarks of JetBrains s.r.o.

Codex and OpenAI are trademarks of OpenAI. Codex assisted with the development of this project.
RevisionGraph is not affiliated with or endorsed by OpenAI.

TortoiseGit and TortoiseSVN names and marks belong to their respective owners. RevisionGraph is not
an official port or endorsed distribution of either project.
