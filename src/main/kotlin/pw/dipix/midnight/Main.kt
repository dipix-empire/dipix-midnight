package pw.dipix.midnight

import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.dataformat.toml.TomlReadFeature
import com.fasterxml.jackson.dataformat.toml.TomlWriteFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
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
import java.util.*
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
    install(HttpTimeout) {
        connectTimeoutMillis = 60*1000
    }
    install(ContentNegotiation) {
        jackson()
    }
    followRedirects = true
}

val downloadHttpClient = HttpClient(CIO) {
    install(UserAgent) {
        agent = "dipix-empire/dipix-midnight/SNAPSHOT (k.krasilnikov.2008@gmail.com)"
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 60*1000
    }
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
        spec.general.dataDir.mkdirs()
        spec.general.configDir.mkdirs()
        terminal.println((bold + minecraftCyan)("Assembling docker-compose.yml..."))
        File("docker-compose.yml").writeText(yamlMapper.writeValueAsString(spec.buildDockerCompose()))
        terminal.println((bold + minecraftGreen)("Docker Compose configuration generated."))
        terminal.println((bold + minecraftCyan)("Downloading jars..."))
        val downloadedJars = runBlocking { spec.servers.mapValues { it.value.downloadJars() ?: mapOf() } }
        terminal.println((bold + minecraftGreen)("Jars downloaded."))
        terminal.println((bold + minecraftCyan)("Applying jars..."))
        spec.servers.forEach { (_, server) ->
            if (server.isPluginModded) {
                downloadedJars[server.name]!!.values.forEach { pluginFile ->
                    pluginFile.copyTo(
                        server.dataDir!!.resolve("plugins").apply { mkdirs() }.resolve(pluginFile.name),
                        overwrite = true
                    )
                }
            } else if (server.isModded) {
                downloadedJars[server.name]!!.values.forEach { modFile ->
                    modFile.copyTo(
                        server.dataDir!!.resolve("mods").apply { mkdirs() }.resolve(modFile.name),
                        overwrite = true
                    )
                }
            }
        }
        terminal.println((bold + minecraftGreen)("Jars applied."))
        terminal.println((bold + minecraftCyan)("Applying configs..."))
        spec.servers.forEach { (_, server) ->
            server.configDir?.apply { mkdirs() }?.listFiles()?.map { it.relativeTo(server.configDir) }?.forEach {
                if (server.type == "velocity" && (it.name == "velocity.toml" || it.name == "forwarding.secret")) { // todo: add support for overlays
                    server.dataDir!!.apply { mkdirs() }.resolve(it)
                        .let { targetFile -> server.configDir.resolve(it).copyTo(targetFile, overwrite = true) }
                } else if (server.isPluginModded) {
                    server.dataDir!!.resolve("plugins").apply { mkdirs() }.resolve(it)
                        .let { targetFile -> server.configDir.resolve(it).copyTo(targetFile, overwrite = true) }
                } else if (server.isModded) {
                    // FIXME: where forge keeps them?
                    server.dataDir!!.resolve("config").apply { mkdirs() }.resolve(it)
                        .let { targetFile -> server.configDir.resolve(it).copyTo(targetFile, overwrite = true) }
                }
            }
        }
        terminal.println((bold + minecraftGreen)("Configs applied!"))
        terminal.println((bold + minecraftCyan)("Applying server.properties..."))
        spec.servers.forEach { (_, server) ->
            if (!server.isProxy) {
                val properties = Properties().apply { putAll(defaultServerProperies) }
                server.properties?.fields()?.forEach { (key, value) -> properties[key] = value.asText() }
                if (server.parent != null) properties["online-mode"] = "false"
                properties.store(server.dataDir!!.apply { mkdirs() }!!.resolve("server.properties").outputStream(), "")
            }
        }
        terminal.println((bold + minecraftGreen)("Properties applied!"))
        terminal.println((bold + minecraftCyan)("Configuring Velocity...")) // todo: support other proxies
        spec.servers.filter { it.value.type == "velocity" }.forEach { (_, server) ->
            val velocityToml: ObjectNode = server.dataDir!!.resolve("velocity.toml").run {
                if (!exists()) {
                    createNewFile()
                    tomlMapper.createObjectNode()
                } else tomlMapper.readValue(this)
            }
            val velocityServers: ObjectNode = velocityToml.putObject("servers")
            val otherServers = spec.servers.filter { !it.value.isProxy }
            server.children!!.map(otherServers::get).requireNoNulls()
                .forEach { velocityServers.put(it.name!!, "${it.name}:${it.defaultPort}") }
            velocityServers.putArray("try").run {
                server.children.map(otherServers::get).requireNoNulls().map(MidnightSpecificationServer::name)
                    .forEach { add(it) }
            }
            tomlMapper.writeValue(server.dataDir.resolve("velocity.toml"), velocityToml)
        }
    }

}