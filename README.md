# RevisionGraph

[![License: GPL v2 or later](https://img.shields.io/badge/License-GPL_v2_or_later-blue.svg)](LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ_Platform-2025.3–2026.2-7B61FF.svg)](https://plugins.jetbrains.com/)

RevisionGraph is a native IntelliJ IDEA plugin for exploring Git history as a clear, interactive
branch graph. It brings the tree-oriented readability of TortoiseGit's RevisionGraph to the
IntelliJ Platform while delegating comparisons, Git Log views, and checkout operations to the
IDE's bundled Git4Idea integration.

The project is under active development and supports IntelliJ IDEA 2025.3 through 2026.2.x.

## Why RevisionGraph?

Large repositories often contain many long-lived branches, remote references, tags, and merge
paths. A compact commit lane is useful for chronological browsing, but it can be difficult to use
when the main question is _how branches relate to one another_. RevisionGraph focuses on that
structural view.

Its layered DAG pipeline uses cost-minimal ranking, crossing reduction, measured node sizes, and
straight polyline routing. Short side branches stay near their common ancestors instead of being
stretched across the entire canvas.

## Features

- Original-style layered Git branch graph with cost-minimal (`OptimalRanking`) layer assignment.
- Local branches, remote branches, tags, HEAD, and undecorated boundary commits.
- Complete multi-reference nodes without overlapping labels.
- Pan, zoom, actual-size, fit-width, fit-height, and fit-entire-graph controls.
- One-click navigation back to the current HEAD.
- Branch and tag locator with Enter/Shift+Enter navigation.
- Revision filtering by range, current branch, or local branches.
- Single and dual revision selection with visually distinct outlines.
- Native Git4Idea comparison against the working tree, HEAD, or another selected revision.
- Native Git Log tabs for a revision or a selected revision range.
- Checkout/switch actions and reference-name copying from the context menu.
- Automatic refresh after repository changes without replacing the tool window.
- English and Simplified Chinese UI.
- Light and dark IntelliJ theme support.

## Requirements

- IntelliJ IDEA 2025.3 through 2026.2.x (platform builds `253`–`262`).
- Git support enabled in the IDE.
- A Git repository configured as a project VCS root.
- JDK 21 or newer when building the plugin from source.

## Installation

### Build from source

```bash
git clone https://github.com/noodles-studio/revision-graph.git
cd revision-graph
./gradlew test buildPlugin
```

The installable archive is generated under `build/distributions/`. In IntelliJ IDEA, open
**Settings | Plugins**, choose **Install Plugin from Disk**, and select the generated ZIP file.

When the JetBrains download service is unavailable, build against a locally installed compatible
IDE:

```bash
./gradlew -PideaPath="/path/to/IntelliJ IDEA" test buildPlugin
```

Marketplace distribution is planned but is not available yet.

## Usage

Open RevisionGraph from the **Git** menu, the project context menu, or
**View | Tool Windows | RevisionGraph**.

- Click a node to select it and show its history in the shared Git Log tab.
- Use <kbd>Ctrl</kbd>/<kbd>Cmd</kbd> + click to select a second node.
- Right-click one node to compare it with the working tree or HEAD, open its history, switch to
  its reference, or copy its reference names.
- Right-click a dual selection to compare the two revisions or open their range history.
- Drag an empty canvas area to pan and use the mouse wheel or toolbar controls to zoom.
- Use the crosshair action to return to HEAD without changing zoom or selection.

## How it works

RevisionGraph reads the decorated repository topology through the Git executable configured by
IntelliJ IDEA. The graph is intentionally reduced to branch, tag, merge, and boundary structure
using `git log --all --parents --simplify-by-decoration`.

The rendering pipeline is implemented in Kotlin and Java2D:

1. Normalize the Git DAG into deterministic topological order.
2. Compute a cost-minimal layer assignment.
3. Insert virtual vertices for edges spanning multiple layers.
4. Reduce crossings with weighted median sweeps.
5. Compact coordinates using measured reference-node bounds.
6. Route and clip straight polylines to node boundaries.

No OGDF native library, JNI, or JNA runtime is bundled. IDEA-native workflows are accessed through
Git4Idea rather than reimplemented by this plugin.

## Privacy

RevisionGraph does not include analytics, telemetry, advertising, or its own network service. It
reads repository history by invoking the locally configured Git executable. Native comparisons,
logs, and checkout operations are handled by the installed IntelliJ Git integration.

## Development

The project compiles against the oldest supported platform, IntelliJ IDEA 2025.3, using a Java 21
bytecode target. JetBrains Plugin Verifier checks the packaged plugin against 2025.3, 2026.1, and
2026.2. See [Architecture](docs/architecture.md) for package boundaries and compatibility policy.

Run the test suite:

```bash
./gradlew test
```

Build and verify the plugin:

```bash
./gradlew buildPlugin verifyPlugin
```

Run a development IDE:

```bash
./gradlew runIde
```

Contributions and issue reports are welcome. Please keep behavior changes covered by focused unit
tests, especially changes to Git parsing, selection rules, or graph layout.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full development and pull request checklist.

## License and provenance

RevisionGraph is free software licensed under the
[GNU General Public License version 2 or any later version](LICENSE) (`GPL-2.0-or-later`).

The project is an independent Kotlin/JVM implementation inspired by the user-visible behavior and
graph semantics of [TortoiseGit RevisionGraph](https://github.com/TortoiseGit/TortoiseGit/tree/master/src/TortoiseProc/RevisionGraph),
which is also distributed under GPL version 2 or later. See [LICENSE-NOTICE.md](LICENSE-NOTICE.md)
for attribution and additional provenance details.

## Credits

- The TortoiseGit and TortoiseSVN contributors for the original RevisionGraph experience.
- JetBrains for the IntelliJ Platform and Git4Idea integration points.
- Built with OpenAI Codex.

RevisionGraph is an independent project and is not affiliated with or endorsed by TortoiseGit,
TortoiseSVN, JetBrains, or OpenAI. Product names and trademarks belong to their respective owners.
