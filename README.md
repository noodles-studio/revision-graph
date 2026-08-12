# RevisionGraph

English | [简体中文](README_zh-CN.md)

[![License: GPL v2 or later](https://img.shields.io/badge/License-GPL_v2_or_later-blue.svg)](LICENSE)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/33494.svg?label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/plugin/33494-revision-graph)

RevisionGraph is a native IntelliJ IDEA plugin that presents Git relationships as a clear, compact,
interactive graph while delegating Git interactions to the IDE's bundled Git4Idea integration.

## Feature Demo

### Repository topology

The overview keeps long-lived branches, release lines, tags, and local or remote references readable
without expanding every commit. Local branch labels show useful upstream divergence directly in the
graph: same-name upstreams stay implicit, while different tracked names and Ahead/Behind counts remain
visible. IntelliJ's Git Log stays available below the graph for detailed commit history.

![RevisionGraph topology with Ahead and Behind tracking status](images/revision-graph-overview.png)

### History and reference filtering

The unified filter keeps history selection and visual reference controls in one place. Limit the
loaded graph by revision range, current branch, or local branches, and independently show or hide
local branches, remote branches, tags, special references, and other reference labels. Hiding a
reference category changes only the labels—the underlying commit topology remains visible.

![RevisionGraph history and reference filter](images/revision-graph-filter.png)

### Revision relationship

Select two revisions to trace each side back to a common visible ancestor. The two paths use the same
colors as their selection outlines, making the point of divergence visible without adding another
summary panel. The selected range can still be inspected through IntelliJ's native Git Log and
comparison workflows.

![RevisionGraph dual-revision relationship paths](images/revision-graph-relationship.png)

## Why RevisionGraph?

Large repositories often contain many long-lived branches, remote references, tags, and merge
paths. A compact commit lane is useful for chronological browsing, but it can be difficult to use
when the main question is _how branches relate to one another_. RevisionGraph focuses on that
structural view.

Its layered DAG pipeline uses cost-minimal ranking, crossing reduction, measured node sizes, and
straight polyline routing. Short side branches stay near their common ancestors instead of being
stretched across the entire canvas.

## Features

- A compact structural view of local branches, remote branches, tags, merges, HEAD, and their
  relationships.
- Clear multi-reference nodes that keep labels readable without turning the graph into oversized
  blocks.
- At-a-glance upstream and Ahead/Behind status without repeating redundant remote branch names.
- Matching-color path highlighting that shows how two selected revisions diverged from a common
  visible ancestor.
- Unified history and reference filtering that can hide labels without removing the underlying
  commit topology.
- Search, HEAD navigation, pan, zoom, and fit controls for exploring large repository graphs.
- Git operations continue through familiar Git4Idea workflows, with automatic graph updates after
  repository changes.
- English and Simplified Chinese UI with light and dark IntelliJ theme support.

## Installation

### JetBrains Marketplace

Install **Revision Graph** from the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33494-revision-graph), or find it under
**Settings | Plugins | Marketplace** inside IntelliJ IDEA.

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

## Usage

Open RevisionGraph from the **Git** menu, the project context menu, or
**View | Tool Windows | RevisionGraph**.

- Click a node to select it and show its history in the shared Git Log tab.
- Use <kbd>Ctrl</kbd>/<kbd>Cmd</kbd> + click to select a second node and reveal their relationship
  paths.
- Right-click one node to compare it with the working tree or HEAD, open its history, switch to its
  reference, create a branch or tag, or copy reference names.
- Right-click a dual selection to compare the two revisions or open their range history.
- Use the filter to limit revision history or independently hide categories of reference labels.
- Drag an empty canvas area to pan and use the mouse wheel or toolbar controls to zoom.
- Use the crosshair action to return to HEAD without changing zoom or selection.
- Use **Fetch** or <kbd>F5</kbd> to invoke IntelliJ's project-level Git Fetch action.

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
reads repository history by invoking the locally configured Git executable. Native Fetch,
comparisons, logs, checkout or switch operations, and reference creation are handled by the installed
IntelliJ Git integration.

## Development

Run the test suite:

```bash
./gradlew verifySourceStyle test
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
