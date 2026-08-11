# Revision Graph

An IntelliJ IDEA 2026.2.x learning MVP that reproduces the core external behavior of
TortoiseGit's RevisionGraph with Kotlin, Swing/Java2D and the configured Git executable.

## Build

Java 25 is required. Run `./gradlew test buildPlugin verifyPluginConfiguration verifyPlugin`.
The installable ZIP is written under `build/distributions/`.
When the JetBrains download CDN is unavailable, use an installed 262 build with
`./gradlew -PideaPath="/path/to/IntelliJ IDEA.app" test buildPlugin`.

The graph uses `git log --all --parents --simplify-by-decoration`, renders one Git root at a
time, supports zoom/pan, reference labels, commit details and native IDEA text diffs.

## Scope and provenance

This is a personal-study MVP and is not prepared for Marketplace publication. Its behavior was
informed by TortoiseGit's RevisionGraph (GPL-2.0-or-later). No TortoiseGit source, OGDF code,
native library, JNI or JNA is included. Review licensing and provenance before redistribution.
