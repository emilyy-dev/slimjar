package io.github.slimjar.task

import com.google.gson.GsonBuilder
import io.github.slimjar.SLIM_API_CONFIGURATION_NAME
import io.github.slimjar.SlimJarPlugin
import io.github.slimjar.func.applySlimLib
import io.github.slimjar.func.slimDefaultDependency
import io.github.slimjar.relocation.RelocationConfig
import io.github.slimjar.relocation.RelocationRule
import io.github.slimjar.resolver.data.Dependency
import io.github.slimjar.resolver.data.DependencyData
import io.github.slimjar.resolver.data.Mirror
import io.github.slimjar.resolver.data.Repository
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.diagnostics.internal.graph.nodes.RenderableDependency
import org.gradle.api.tasks.diagnostics.internal.graph.nodes.RenderableModuleResult
import java.io.File
import java.io.FileWriter
import java.net.URL
import javax.inject.Inject

@CacheableTask
open class SlimJar @Inject constructor(private val config: Configuration) : DefaultTask() {

    // Find by name since it won't always be present
    private val apiConfig = project.configurations.findByName(SLIM_API_CONFIGURATION_NAME)

    private val relocations = mutableSetOf<RelocationRule>()
    private val mirrors = mutableSetOf<Mirror>()
    private val isolatedProjects = mutableSetOf<Project>()

    init {
        group = "slimJar"
    }

    open fun relocate(original: String, relocated: String): SlimJar {
        return addRelocation(original, relocated, null)
    }

    open fun relocate(original: String, relocated: String, configure: Action<RelocationConfig>): SlimJar {
        return addRelocation(original, relocated, configure)
    }

    open fun mirror(mirror: String, original: String) {
        mirrors.add(Mirror(URL(mirror), URL(original)))
    }

    open infix fun String.mirroring(original: String) {
        mirrors.add(Mirror(URL(this), URL(original)))
    }

    open fun isolate(proj: Project) {
        isolatedProjects.add(proj)

        runCatching {
            proj.pluginManager.apply(SlimJarPlugin::class.java)
        }

        // Adds slimJar as compileOnly
        if (proj.slimDefaultDependency) {
            proj.applySlimLib("compileOnly")
        }

        val shadowTask = proj.getTasksByName("shadowJar", true).firstOrNull()
        val jarTask = shadowTask ?: proj.getTasksByName("jar", true).firstOrNull()
        jarTask?.let {
            dependsOn(it)
        }
    }

    /**
     * Action to generate the json file inside the jar
     */
    @TaskAction
    internal fun createJson() = with(project) {

        val dependencies =
            RenderableModuleResult(config.incoming.resolutionResult.root)
                .children
                .mapNotNull {
                    it.toSlimDependency()
                }.toMutableSet()

        // If api config is present map dependencies from it as well
        apiConfig?.let { config ->
            dependencies.addAll(
                RenderableModuleResult(config.incoming.resolutionResult.root)
                    .children
                    .mapNotNull {
                        it.toSlimDependency()
                    }
            )
        }

        val repositories = repositories.filterIsInstance<MavenArtifactRepository>()
            .filterNot { it.url.toString().startsWith("file") }
            .toSet()
            .map { Repository(it.url.toURL()) }

        // Note: Commented out to allow creation of empty dependency file
        // if (dependencies.isEmpty() || repositories.isEmpty()) return

        val folder = File("${buildDir}/resources/main/")
        if (folder.exists().not()) folder.mkdirs()

        FileWriter(File(folder, "slimjar.json")).use {
            GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(DependencyData(mirrors, repositories, dependencies, relocations), it)
        }
    }

    // Finds jars to be isolated and adds them to final jar
    @TaskAction
    internal fun includeIsolatedJars() = with(project) {
        isolatedProjects.filter { it != this }.forEach {
            val shadowTask = it.getTasksByName("shadowJar", true).firstOrNull()
            val jarTask = shadowTask ?: it.getTasksByName("jar", true).firstOrNull()
            jarTask?.let { task ->
                val archive = task.outputs.files.singleFile
                val folder = File("${buildDir}/resources/main/")
                if (folder.exists().not()) folder.mkdirs()
                val output = File(folder, "${it.name}.isolated-jar")
                archive.copyTo(output, true)
            }
        }
    }

    /**
     * Internal getter required because Gradle will think an internal property is an action
     */
    internal fun relocations(): Set<RelocationRule> {
        return relocations
    }

    /**
     * Adds a relocation to the list, method had to be separated because Gradle doesn't support default values
     */
    private fun addRelocation(
        original: String,
        relocated: String,
        configure: Action<RelocationConfig>? = null
    ): SlimJar {
        val relocationConfig = RelocationConfig()
        configure?.execute(relocationConfig)
        val rule = RelocationRule(original, relocated, relocationConfig.exclusions, relocationConfig.inclusions)
        relocations.add(rule)
        return this
    }

}

/**
 * Turns a [RenderableDependency] into a [Dependency]] with all its transitives
 */
private fun RenderableDependency.toSlimDependency(): Dependency? {
    val transitive = mutableSetOf<Dependency>()
    collectTransitive(transitive, children)
    return id.toString().toDependency(transitive)
}

/**
 * Recursively flattens the transitive dependencies
 */
private fun collectTransitive(transitive: MutableSet<Dependency>, dependencies: Set<RenderableDependency>) {
    for (dependency in dependencies) {
        val dep = dependency.id.toString().toDependency(emptySet()) ?: continue
        if (dep in transitive) continue
        transitive.add(dep)
        collectTransitive(transitive, dependency.children)
    }
}

/**
 * Creates a [Dependency] based on a string
 * group:artifact:version:snapshot - The snapshot is the only nullable value
 */
private fun String.toDependency(transitive: Set<Dependency>): Dependency? {
    val values = split(":")
    val group = values.getOrNull(0) ?: return null
    val artifact = values.getOrNull(1) ?: return null
    val version = values.getOrNull(2) ?: return null
    val snapshot = values.getOrNull(3)

    return Dependency(group, artifact, version, snapshot, transitive)
}