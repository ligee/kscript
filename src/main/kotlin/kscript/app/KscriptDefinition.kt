package kscript.app

import org.jetbrains.kotlin.mainKts.impl.IvyResolver
import org.jetbrains.kotlin.script.util.resolvers.DirectResolver
import org.jetbrains.kotlin.script.util.resolvers.FlatLibDirectoryResolver
import org.jetbrains.kotlin.script.util.resolvers.experimental.BasicArtifactCoordinates
import org.jetbrains.kotlin.script.util.resolvers.experimental.BasicRepositoryCoordinates
import org.jetbrains.kotlin.script.util.resolvers.experimental.GenericArtifactCoordinates
import java.io.File
import java.net.URL
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileBasedScriptSource
import kotlin.script.experimental.host.UrlScriptSource
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

@Suppress("unused", "UNUSED_PARAMETER")
@KotlinScript(
        fileExtension = "kts",
        compilationConfiguration = KscriptCompilationConfiguration::class
)
abstract class KscriptDefinition(val args: Array<String>)

object KscriptCompilationConfiguration : ScriptCompilationConfiguration(
        {
            defaultImports(DependsOn::class, DependsOnMaven::class, Repository::class)
            jvm {
                dependenciesFromClassContext(KscriptDefinition::class, wholeClasspath = true)
            }
            refineConfiguration {
                onAnnotations(
                        DependsOn::class, DependsOnMaven::class, Repository::class, MavenRepository::class, Import::class,
                        handler = KscriptConfigurationRefinerFromAnnotations()
                )
                beforeCompiling(KscriptConfigurationRefinerFromDirectives())
            }
        }
)

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class DependsOn(vararg val values: String)

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class DependsOnMaven(vararg val values: String)

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class Repository(val value: String = "")

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class MavenRepository(val value: String = "")

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class Import(val path: String = "")

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class CompilerOptions(vararg val options: String)

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class KotlinOptions(vararg val options: String)

abstract class KscriptConfigurationRefinerBase : RefineScriptCompilationConfigurationHandler {

    override operator fun invoke(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {

        val diagnostics = arrayListOf<ScriptDiagnostic>()

        val updatedConfiguration = ScriptCompilationConfiguration(context.compilationConfiguration) {

            updateConfiguration(context, diagnostics)
        }

        return updatedConfiguration.asSuccess(diagnostics)
    }

    protected abstract fun ScriptCompilationConfiguration.Builder.updateConfiguration(
            context: ScriptConfigurationRefinementContext,
            diagnostics: ArrayList<ScriptDiagnostic>
    )

    private val resolvers by lazy { arrayListOf(DirectResolver(), IvyResolver()) }

    protected fun resolveDependencies(
            diagnostics: ArrayList<ScriptDiagnostic>,
            repositories: List<BasicRepositoryCoordinates>,
            dependencyCoords: List<GenericArtifactCoordinates>
    ): List<File>? {

        for (repoCoord in repositories) {
            val isFlat: Boolean = resolvers.firstIsInstanceOrNull<FlatLibDirectoryResolver>()?.tryAddRepository(repoCoord)
                    ?: (FlatLibDirectoryResolver.tryCreate(repoCoord)?.also { resolvers.add(it) } != null)
            if (!isFlat) {
                resolvers.find { it !is FlatLibDirectoryResolver && it.tryAddRepository(repoCoord) }
                        ?: diagnostics.add(ScriptDiagnostic("Unknown repository: $repoCoord"))
            }
        }

        return dependencyCoords.flatMap { dep ->
            resolvers.asSequence().mapNotNull { it.tryResolve(dep) }.firstOrNull() ?: emptyList()
        }
    }
}

class KscriptConfigurationRefinerFromAnnotations : KscriptConfigurationRefinerBase() {

    override fun ScriptCompilationConfiguration.Builder.updateConfiguration(
            context: ScriptConfigurationRefinementContext, diagnostics: ArrayList<ScriptDiagnostic>
    ) {
        val annotations = context.collectedData?.get(ScriptCollectedData.foundAnnotations)

        val repositories = annotations?.mapNotNull {
            when (it) {
                is Repository -> BasicRepositoryCoordinates(it.value)
                is MavenRepository -> BasicRepositoryCoordinates(it.value)
                else -> null
            }
        }.orEmpty()

        val dependencyCoords =
                (annotations?.flatMap {
                    when(it) {
                        is DependsOn -> it.values.asIterable()
                        is DependsOnMaven -> it.values.asIterable()
                        else -> emptyList()
                    }
                }.orEmpty())
                .map { BasicArtifactCoordinates(it) }

        resolveDependencies(diagnostics, repositories, dependencyCoords)?.let {
            dependencies.append(JvmDependency(it))
        }

        annotations?.forEach { annotation ->
            when (annotation) {
                is Import -> importScripts.append(File(annotation.path).toScriptSource())
                is CompilerOptions -> compilerOptions.append(*annotation.options)
            }
        }
    }
}

class KscriptConfigurationRefinerFromDirectives : KscriptConfigurationRefinerBase() {

    override fun ScriptCompilationConfiguration.Builder.updateConfiguration(
            context: ScriptConfigurationRefinementContext, diagnostics: ArrayList<ScriptDiagnostic>
    ) {

        val script = context.script

        val includeContext = when {
            script is FileBasedScriptSource -> script.file.parentFile.toURI()
            script is ExternalSourceCode -> with(script.externalLocation) { URL(protocol, host, port, File(file).parent).toURI() }
            else -> File(".").toURI()
        }

        val dependenciesFromDirectives = ArrayList<String>()

        fun String.ifDirective(directive: String, handler: (String) -> Unit): String? =
            if (startsWith(directive)) {
                handler(substring(directive.length).trim())
                null
            } else this

        for (line in script.text.splitToSequence('\r', '\n')) {
            line.takeIf { it.isNotBlank() }
                    ?.ifDirective(INCLUDE_DIRECTIVE_PREFIX) {
                        importScripts.append(UrlScriptSource(includeToUrl(it, includeContext)))
                    }
                    ?.ifDirective(DEPS_COMMENT_PREFIX) {
                        dependenciesFromDirectives.addAll(extractDependencies(line))
                    }
                    ?.ifDirective(COMPILER_OPTIONS_PREFIX) {
                        compilerOptions.append(it)
                    }
        }

        if (dependenciesFromDirectives.isNotEmpty()) {
            val dependencyCoords = dependenciesFromDirectives.map { BasicArtifactCoordinates(it) }

            resolveDependencies(diagnostics, emptyList(), dependencyCoords)?.let {
                dependencies.append(JvmDependency(it))
            }
        }
    }

}

// TODO: replace with stdlib version when available
private inline fun <reified T : Any> List<*>.firstIsInstanceOrNull(): T? {
    for (element in this) if (element is T) return element
    return null
}