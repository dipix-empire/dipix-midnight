package pw.dipix.midnight

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.ajalt.mordant.animation.ProgressAnimation
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import kotlin.system.exitProcess

val jacksonKotlinModule = KotlinModule.Builder()
    .withReflectionCacheSize(512)
    .configure(KotlinFeature.NullToEmptyCollection, false)
    .configure(KotlinFeature.NullToEmptyMap, false)
    .configure(KotlinFeature.NullIsSameAsDefault, true)
    .configure(KotlinFeature.SingletonSupport, false)
    .configure(KotlinFeature.StrictNullChecks, true)
    .build()

val tomlMapper = TomlMapper().registerModules(
    jacksonKotlinModule
).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
val yamlMapper = YAMLMapper.builder().addModule(jacksonKotlinModule)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

val httpClient = HttpClient(CIO) {
    install(UserAgent) {
        agent = "dipix-empire/dipix-midnight/SNAPSHOT (k.krasilnikov.2008@gmail.com)"
    }
    install(HttpTimeout)
    install(ContentNegotiation) {
        jackson()
    }
    followRedirects = true
}

val downloadHttpClient = HttpClient(CIO) {
    install(UserAgent) {
        agent = "dipix-empire/dipix-midnight/SNAPSHOT (k.krasilnikov.2008@gmail.com)"
    }
    install(HttpTimeout)
    followRedirects = true
}

val terminal = Terminal()

fun main(args: Array<String>): Unit = exitProcess(CommandLine(MidnightMainCommand).execute(*args))

@Command(
    name = "midnight",
    mixinStandardHelpOptions = true,
    version = ["SNAPSHOT"],
    subcommands = [MidnightBuildCommand::class]
)
object MidnightMainCommand : Runnable {
    override fun run() {
        terminal.println("${(bold + minecraftYellow)("Di")}${(bold + minecraftCyan)("Pix")} ${(bold + minecraftPurple)("Midnight")}")
        terminal.println("--help for help")
    }

}

@Command(name = "build")
object MidnightBuildCommand : Runnable {
    @Option(
        names = ["-f", "--file"],
        description = ["The spec.midnight.toml to use for building"],
        defaultValue = "./spec.midnight.toml"
    )
    var specFile: File = File("./spec.midnight.toml")
    override fun run() {
        terminal.println((bold + minecraftCyan)("Starting build process..."))
        terminal.println("Using specification located at ${specFile.absolutePath}")
        val spec: MidnightSpecification = tomlMapper.readValue<MidnightSpecification>(specFile).toResolved()
        terminal.println((bold + minecraftGreen)("Specification parsed."))
        terminal.println("Detected servers: ${spec.servers.keys.joinToString()}")
        terminal.println("Root server: ${spec.rootServer!!.name!!}")
        terminal.println((bold + minecraftCyan)("Assembling docker-compose.yml..."))
        File("docker-compose.yml").writeText(yamlMapper.writeValueAsString(spec.buildDockerCompose()))
        terminal.println((bold + minecraftGreen)("Docker Compose configuration generated."))
        terminal.println((bold + minecraftCyan)("Downloading jars..."))
        spec.servers.forEach { name, server ->
            terminal.println("Server $name:")
            val jars = if (server.isModded) server.mods else if (server.isPluginModded) server.plugins else null
            val folder =
                (if (server.isModded) "mods" else if (server.isPluginModded) "plugins" else null)?.let { File(it) }
            val tasks = jars?.mapValues {
                MidnightJarSource.parseAndGetJar(it.key, it.value, server)
                    ?: throw RuntimeException("${it.key} did not resolve.")
            }?.map { URLDownloadable(it.value.second).toTask(folder!!.resolve("${it.value.first}.jar")) }
            println("Tasks: $tasks")
            runBlocking { tasks?.downloadAll() }
        }
    }

}

