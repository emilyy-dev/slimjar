import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.20"
    `java-gradle-plugin`
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "0.12.0"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    `maven-publish`
}

group = "io.github.slimjar"
version = "1.1.0"

repositories {
    maven("https://plugins.gradle.org/m2/")
}

val shadowImplementation: Configuration by configurations.creating
configurations["compileOnly"].extendsFrom(shadowImplementation)
configurations["testImplementation"].extendsFrom(shadowImplementation)

dependencies {
    shadowImplementation(kotlin("stdlib"))
    shadowImplementation(project(":slimjar"))
    shadowImplementation("com.google.code.gson:gson:2.8.6")

    compileOnly("com.github.jengelman.gradle.plugins:shadow:6.1.0")

    testImplementation("com.github.jengelman.gradle.plugins:shadow:6.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.7.0")
    testImplementation("org.assertj:assertj-core:3.19.0")
}

val shadowJarTask = tasks.named("shadowJar", ShadowJar::class.java)
val relocateShadowJar = tasks.register("relocateShadowJar", ConfigureShadowRelocation::class.java) {
    target = shadowJarTask.get()
}

shadowJarTask.configure {
    dependsOn(relocateShadowJar)
    archiveClassifier.set("")
    configurations = listOf(shadowImplementation)
}

// Required for plugin substitution to work in samples project
artifacts {
    add("runtimeOnly", shadowJarTask)
}

tasks.whenTaskAdded {
    if (name == "publishPluginJar" || name == "generateMetadataFileForPluginMavenPublication") {
        dependsOn(tasks.named("shadowJar"))
    }
}

// Disabling default jar task as it is overridden by shadowJar
tasks.named("jar").configure {
    enabled = false
}

val ensureDependenciesAreInlined by tasks.registering {
    description = "Ensures all declared dependencies are inlined into shadowed jar"
    group = HelpTasksPlugin.HELP_GROUP
    dependsOn(tasks.shadowJar)

    doLast {
        val nonInlinedDependencies = mutableListOf<String>()
        zipTree(tasks.shadowJar.flatMap { it.archiveFile }).visit {
            if (!isDirectory) {
                val path = relativePath
                if (
                    !path.startsWith("META-INF") &&
                    path.lastName.endsWith(".class") &&
                    !path.pathString.startsWith(
                        "io.github.slimjar".replace(".", "/")
                    )
                ) {
                    nonInlinedDependencies.add(path.pathString)
                }
            }
        }
        if (nonInlinedDependencies.isNotEmpty()) {
            throw GradleException("Found non inlined dependencies: $nonInlinedDependencies")
        }
    }
}

tasks.named("check") {
    dependsOn(ensureDependenciesAreInlined)
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }

    withType<ShadowJar> {
        mapOf(
            "kotlin" to ".kotlin",
            "io.github.slimjar" to "",
            "me.lucko.jarrelocator" to ".jarrelocator",
            "com.google.gson" to ".gson"
        ).forEach { relocate(it.key, "io.github.slimjar${it.value}") }
    }

    test {
        useJUnitPlatform()
    }
}

// Work around publishing shadow jars
afterEvaluate {
    publishing {
        publications {
            withType<MavenPublication> {
                if (name == "pluginMaven") {
                    setArtifacts(listOf(shadowJarTask.get()))
                }
            }
        }
    }
}

gradlePlugin {
    plugins {
        create("slimjar") {
            id = "io.github.slimjar"
            displayName = "SlimJar"
            description = "JVM Runtime Dependency Management."
            implementationClass = "io.github.slimjar.SlimJarPlugin"
        }
    }
}

pluginBundle {
    website = "https://github.com/SlimJar/slimjar"
    vcsUrl = "https://github.com/SlimJar/slimjar"
    tags = listOf("runtime dependency", "relocation")
    description = "Very easy to setup and downloads any public dependency at runtime!"
}
