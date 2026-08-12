# Contributing to RevisionGraph

Thank you for helping improve RevisionGraph.

## Development environment

- JDK 21 or newer.
- Git available on `PATH`.
- IntelliJ IDEA 2025.3 or newer for interactive development.

The project compiles against the oldest supported IntelliJ Platform release. Do not raise the
compile-time platform merely to use a newer API unless the minimum supported version is being
changed deliberately.

## Before opening a pull request

Run the focused checks first:

```bash
./gradlew test
./gradlew buildPlugin verifyPluginStructure verifyPluginProjectConfiguration
```

Run the full compatibility matrix before a release or after changing IntelliJ/Git4Idea API usage:

```bash
./gradlew verifyPlugin
```

To diagnose one target independently, pass its release version:

```bash
./gradlew verifyPlugin -PverifierIdeVersion=2025.3.6
```

## Architecture rules

- Keep `model` independent of IntelliJ Platform and Swing APIs.
- Keep graph calculation in `layout`; it must remain deterministic and independently testable.
- Keep Git history acquisition and parsing in `git`.
- Coordinate Git loading and layout in `application`.
- Isolate Git4Idea and IntelliJ workflow integrations in `platform`.
- Keep presentation state and Swing components in `ui`.
- Do not introduce dependencies from `model`, `git`, or `layout` back to `ui`.

Behavior changes should include focused tests. Layout changes should cover topology and stable
ordering; parser changes should cover malformed and boundary input; selection changes should cover
single, additive, and context-click behavior.

## Commit and pull request guidance

Keep commits small and explain why a change is needed. Pull requests should describe user-visible
behavior, compatibility impact, and the verification commands that were run.
