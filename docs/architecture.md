# Architecture

RevisionGraph uses a single Gradle module with package-level layers. A multi-module build would add
significant plugin packaging overhead without improving the current deployment boundary.

## Dependency direction

```text
ui ────────────────► application ─────► git
│                         │              │
│                         ├────────────► layout
│                         └────────────► model
│
└──────────────────► platform ─────────► model
```

- `model` contains Git graph and revision value objects.
- `git` invokes the IDE-configured Git executable and parses repository data.
- `layout` contains deterministic graph ranking, crossing reduction, routing, and spatial lookup.
- `application` coordinates repository loading and layout creation.
- `platform` adapts IntelliJ and Git4Idea workflows such as comparison, checkout, and Git Log tabs.
- `ui` owns Swing rendering, user interaction, dialogs, and the tool-window lifecycle.

The `platform` package is the compatibility boundary. APIs that are not part of the stable IntelliJ
Platform surface must not leak into application, layout, model, or UI state classes.

Focused Git Log tabs require JetBrains' experimental VCS Log UI API. Those calls are intentionally
confined to `platform/RevisionLogService.kt` and covered by the versioned Plugin Verifier matrix.

## Compatibility policy

The plugin compiles against IntelliJ IDEA 2025.3 with Java 21. JetBrains Plugin Verifier checks the
built artifact against 2025.3, 2026.1, and 2026.2. The plugin descriptor intentionally has no
`until-build`; future releases remain installable, while CI and Marketplace verification provide
the compatibility signal.

## Concurrency

Git history and graph layout run in a cancellable background task. Cancellation crosses the
application and Git layers as a plain function, so core code is not coupled to IntelliJ progress
classes. Swing state is published only after the request generation is checked on task completion.
