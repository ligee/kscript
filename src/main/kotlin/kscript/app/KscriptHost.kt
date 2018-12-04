package kscript.app

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.with
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

class KscriptHost(cacheDir: File) {

    private val cache = KscriptCache(cacheDir)
    private val compiler = JvmScriptCompiler(defaultJvmScriptingHostConfiguration.with {
        jvm {
            compilationCache(cache)
        }
    })
    private val evaluator = BasicJvmScriptEvaluator()
    private val scriptCompilationConfiguration = createJvmCompilationConfigurationFromTemplate<KscriptDefinition>()

    fun eval(script: SourceCode, kscriptArgs: List<String>): Int {
        val result = runBlocking {
            compiler(script, scriptCompilationConfiguration).onSuccess {
                val evalConfiguration = ScriptEvaluationConfiguration {
                    constructorArgs(kscriptArgs.toTypedArray())
                }
                evaluator(it, evalConfiguration)
            }.onFailure { res ->
                throw Exception("Compilation/evaluation failed:\n  ${res.reports.joinToString("\n  ") {
                    it.exception?.toString() ?: it.message
                }}")
            }
        }
        return when (result) {
            is ResultWithDiagnostics.Success -> 0
            else -> 1
        }
    }
}

internal class KscriptCache(private val baseDir: File) :
    CompiledScriptJarsCache(
        { script, scriptCompilationConfiguration ->
            File(baseDir, "compiledScript-" + uniqueHash(script, scriptCompilationConfiguration))
        }
    )

private fun uniqueHash(script: SourceCode, scriptCompilationConfiguration: ScriptCompilationConfiguration): String {
    val digestWrapper = MessageDigest.getInstance("MD5")
    digestWrapper.update(script.text.toByteArray())
    scriptCompilationConfiguration.entries().sortedBy { it.key.name }.forEach {
        digestWrapper.update(it.key.name.toByteArray())
        digestWrapper.update(it.value.toString().toByteArray())
    }
    return digestWrapper.digest().toHexString()
}

private fun ByteArray.toHexString(): String = joinToString("", transform = { "%02x".format(it) })

