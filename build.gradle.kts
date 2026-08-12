import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.noodles_studio"
version = "0.1.0"

kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val ideaPath = providers.gradleProperty("ideaPath").orNull
        if (ideaPath == null) intellijIdea("2025.3.6") else local(ideaPath)
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.log")
        bundledModule("intellij.platform.vcs.log.impl")
        pluginVerifier()
        zipSigner()
    }
    testImplementation(kotlin("test"))
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "Revision Graph"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "253"
        }
    }
    pluginVerification {
        ides {
            when (val verifierIdeVersion = providers.gradleProperty("verifierIdeVersion").orNull) {
                null -> {
                    create(IntelliJPlatformType.IntellijIdea, "2025.3.6")
                    create(IntelliJPlatformType.IntellijIdea, "2026.1.4")
                    create(IntelliJPlatformType.IntellijIdea, "2026.2.1")
                }
                else -> create(IntelliJPlatformType.IntellijIdea, verifierIdeVersion)
            }
        }
    }
}

tasks {
    test { useJUnitPlatform() }
    runIde { jvmArgs("-Xmx2g") }
    processResources {
        from("LICENSE") { into("META-INF") }
        from("LICENSE-NOTICE.md") { into("META-INF") }
    }
}
