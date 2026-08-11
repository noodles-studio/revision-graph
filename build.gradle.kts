plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.fh00126072001.revisiongraph"
version = "0.1.0"

kotlin { jvmToolchain(25) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val ideaPath = providers.gradleProperty("ideaPath").orNull
        if (ideaPath == null) intellijIdea("2026.2.0.1") else local(ideaPath)
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.log")
        bundledModule("intellij.platform.vcs.log.impl")
        pluginVerifier()
        zipSigner()
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        name = "Revision Graph"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
    pluginVerification { ides { recommended() } }
}

tasks {
    test { useJUnitPlatform() }
    runIde { jvmArgs("-Xmx2g") }
}
