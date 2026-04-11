plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.mindmap.plugin"
version = "1.0.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")

        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")

        pluginVerifier()
        zipSigner()
    }

    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("253.*")
    }

    runIde {
        if (project.hasProperty("org.gradle.java.home")) {
            environment("JAVA_HOME", project.property("org.gradle.java.home").toString())
        }
    }

    buildSearchableOptions {
        enabled = false
    }
}
