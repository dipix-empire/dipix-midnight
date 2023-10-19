package pw.dipix.midnight

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
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
import picocli.CommandLine.*
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

val jacksonTomlMapper = TomlMapper().registerModules(
    jacksonKotlinModule
).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)!!
val jacksonYamlMapper = YAMLMapper.builder().addModule(jacksonKotlinModule)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()!!

val httpClient = HttpClient(CIO) {
    install(UserAgent) {
        agent = "dipix-empire/dipix-midnight/SNAPSHOT (k.krasilnikov.2008@gmail.com)"
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 60 * 1000
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
        connectTimeoutMillis = 60 * 1000
    }
    followRedirects = true
}

val terminal = Terminal()
val tokens =
    File("tokens.properties").let { file -> if (file.exists()) Properties().apply { load(file.inputStream()) } else Properties() }

fun main(args: Array<String>): Unit = exitProcess(CommandLine(MidnightMainCommand).execute(*args))

@Command(
    name = "midnight",
    mixinStandardHelpOptions = true,
    versionProvider = MidnightVersionProvider::class,
    subcommands = [MidnightBuildCommand::class, MidnightAddCommand::class, MidnightUpgradeCommand::class, HelpCommand::class],
    description = ["reproducible minecraft clusters with minimal config"]
)
object MidnightMainCommand : Runnable {

    @Option(names = ["--print-debug", "--debug"], description = ["Prints additional debug information"], negatable = true)
    var printDebug: Boolean = false
    override fun run() {
        terminal.println(
            "${(bold + minecraftYellow)("Di")}${(bold + minecraftCyan)("Pix")} ${
                (bold + minecraftDarkPurple)(
                    "Midnight"
                )
            }"
        )
        terminal.println("[--]help for help")
    }
}

object MidnightVersionProvider : IVersionProvider {
    private val versionYaml: ObjectNode by lazy { jacksonYamlMapper.readValue(MidnightSpecification::class.java.getResource("/version.yaml")!!) }
    val version by lazy { versionYaml.get("version")!!.asText() }
    val gitCommit by lazy { versionYaml.get("git-commit")!!.asText() }

    override fun getVersion(): Array<String> = arrayOf(
        "${(bold + minecraftYellow)("Di")}${(bold + minecraftCyan)("Pix")} ${
            (bold + minecraftDarkPurple)(
                "Midnight"
            )
        }",
        "Version: $version",
        "Commit: $gitCommit"
    )
}

@Command(name = "build", description = ["Build server according to specifications, ready to use."])
object MidnightBuildCommand : Runnable {
    @Option(
        names = ["-f", "--file"],
        description = ["The spec.midnight.toml to use for building.", "NOTE: build will happen in current directory, NOT in directory of specification."], // FIXME
        defaultValue = "./spec.midnight.toml"
    )
    var specFile: File = File("./spec.midnight.toml")
    override fun run() {
        terminal.println((bold + minecraftCyan)("Starting build process..."))
        if (tokens["github"] == null) {
            terminal.println((minecraftYellow + bold)("No GitHub Token supplied. Requests are limited to 60/hour."))
            terminal.println((minecraftYellow + bold)("You can add your token by creating tokens.properties and adding github=<token> into it."))
        }
        terminal.println("Using specification located at ${specFile.absolutePath}")
        val spec: MidnightSpecification = jacksonTomlMapper.readValue<MidnightSpecification>(specFile).toResolved()
        terminal.println((bold + minecraftGreen)("Specification parsed."))
        terminal.println("Detected servers: ${spec.servers.keys.joinToString()}")
        terminal.println("Root server: ${spec.rootServer!!.name!!}")
        spec.servers.forEach { (name, server) ->
            terminal.println((minecraftLightPurple + bold)("Server $name:"))
            terminal.println("Type ${server.type}")
            terminal.println("Minecraft ${server.minecraftVersion}")
            terminal.println("Software ${server.softwareVersion}")
            terminal.println("Build ${server.softwareBuild}")
        }
        spec.general.dataDir.mkdirs()
        spec.general.configDir.mkdirs()
        terminal.println((bold + minecraftCyan)("Assembling docker-compose.yml..."))
        val specCompose = spec.buildDockerCompose()
        val overlayCompose =
            File("./docker-compose.overlay.yml").run { if (exists()) jacksonYamlMapper.readValue<ObjectNode>(this) else null }
        if (overlayCompose != null) specCompose.mergeWith(overlayCompose)
        jacksonYamlMapper.writeValue(File("./docker-compose.yml"), specCompose)
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
        listOf("mods", "plugins").map { File(it) }.forEach { it.deleteRecursively() }
        terminal.println((bold + minecraftCyan)("Applying configs..."))
        spec.servers.forEach { (_, server) ->
            server.configDir?.apply { mkdirs() }?.listFiles()?.map { it.relativeTo(server.configDir) }?.forEach {
                /*if (server.type == "velocity" && (it.name == "velocity.toml" || it.name == "forwarding.secret")) {
                    server.dataDir!!.apply { mkdirs() }.resolve(it)
                        .let { targetFile -> server.configDir.resolve(it).copyTo(targetFile, overwrite = true) }
                } else*/ if (server.isPluginModded) {
                server.dataDir!!.resolve("plugins").apply { mkdirs() }.resolve(it)
                    .let { targetFile -> server.configDir.resolve(it).copyRecursively(targetFile, overwrite = true) }
            } else if (server.isModded) {
                // FIXME: where forge keeps them?
                server.dataDir!!.resolve("config").apply { mkdirs() }.resolve(it)
                    .let { targetFile -> server.configDir.resolve(it).copyRecursively(targetFile, overwrite = true) }
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
                properties.store(server.dataDir!!.apply { mkdirs() }.resolve("server.properties").outputStream(), "")
            }
        }
        terminal.println((bold + minecraftGreen)("Properties applied!"))
        terminal.println((bold + minecraftCyan)("Applying overlays..."))
        spec.servers.forEach { (_, server) ->
            server.overlayDir?.apply { mkdirs() }?.listFiles()?.map { it.relativeTo(server.overlayDir) }?.forEach {
                server.dataDir!!.apply { mkdirs() }.resolve(it)
                    .let { targetFile -> server.overlayDir.resolve(it).copyRecursively(targetFile, overwrite = true) }
            }
        }
        terminal.println((bold + minecraftGreen)("Overlays applied!"))
        terminal.println((bold + minecraftCyan)("Configuring Velocity...")) // todo: support other proxies
        // you are not supposed to have multiple proxies.
        spec.servers.entries.first { it.value.type == "velocity" }.let { (_, server) ->
            val velocityToml: ObjectNode = server.dataDir!!.resolve("velocity.toml").run {
                if (!exists()) {
                    createNewFile()
                    jacksonTomlMapper.createObjectNode()
                } else jacksonTomlMapper.readValue(this)
            }
            val velocityServers: ObjectNode = velocityToml.putObject("servers")
            val otherServers = spec.servers.filter { !it.value.isProxy }
            server.children!!.map(otherServers::get).requireNoNulls()
                .forEach { velocityServers.put(it.name!!, "${it.name}:${it.defaultPort}") }
            velocityServers.putArray("try").run {
                server.children.map(otherServers::get).requireNoNulls().map(MidnightSpecificationServer::name)
                    .forEach { add(it) }
            }
            jacksonTomlMapper.writeValue(server.dataDir.resolve("velocity.toml"), velocityToml)
        }
        terminal.println((bold + minecraftGreen)("Velocity configured!"))
    }

}

@Command(name = "add", description = ["Add one or more jars."])
object MidnightAddCommand : Runnable {
    @Parameters(index = "0", description = ["Server for jars to be added"])
    lateinit var targetServer: String

    @Parameters(index = "1..*", arity = "1..*", description = ["Jar indentifiers. Supports any resolver defined in spec."])
    lateinit var toAdd: List<String>

    @Option(
        names = ["-f", "--file"],
        description = ["The spec.midnight.toml to use for building"],
        defaultValue = "./spec.midnight.toml"
    )
    var specFile: File = File("./spec.midnight.toml")

    @Option(names = ["--dry"], description = ["Only tells what is going to happen."])
    var dryRun = false
    @Option(names = ["--pin"], description = ["Pin stars (*) to specific version"])
    var pin = false

    val jarRegex = Regex("""(?<name>[^=]+)(=(?<version>.+))?""")
    val tomlJarsRegex = { server: String ->
        Regex(
            """((?<=\[server\.['"]?$server['"]?\.(mods|plugins)\]\n)[^\[]*+(?=\[?))""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }

    override fun run() {
        val spec: MidnightSpecification =
            jacksonTomlMapper.readValue<MidnightSpecification>(MidnightBuildCommand.specFile).toResolved()
        terminal.println("${(minecraftCyan + bold)("Adding jars:")} ${(minecraftLightPurple + bold)(toAdd.joinToString(", "))}")
        val server = spec.servers[targetServer] ?: throw IllegalArgumentException("Specified server not found")
        val parsedVersions =
            toAdd.map { jarRegex.matchEntire(it) }
                .associate { it!!.groups["name"]!!.value to it.groups["version"]?.value }
        val resolved =
            parsedVersions
                .mapValues {
                    MidnightJarSource.parseAndGetJar(
                        it.key,
                        it.value ?: "*",
                        server
                    )
                }
        if (resolved.any { it.value.url == null }) {
            throw RuntimeException(
                "Couldn't resolve: ${
                    resolved.filter { it.value.url == null }.entries.joinToString(", ") { it.key }
                }"
            )
        }
        if (parsedVersions.any { server.mods?.contains(it.key) == true || server.plugins?.contains(it.key) == true }) {
            throw RuntimeException(
                "Following jars already exist: ${
                    parsedVersions.filter {
                        server.mods?.contains(it.key) == true || server.plugins?.contains(
                            it.key
                        ) == true
                    }.keys.joinToString(", ")
                }".run { if(pin) "$this\nTrying to pin a star? Use upgrade command instead!" else this }
            )
        }
        val tomlJars = resolved.entries.joinToString("\n") { "${it.key}=\"${if(pin) it.value.version else (parsedVersions[it.key] ?: "*")}\"" }
        terminal.println((minecraftCyan + bold)("Adding following to spec:"))
        terminal.println(tomlJars)
        if (dryRun) {
            terminal.println((minecraftGreen + bold)("Dry run finished."))
            return
        }
        try {
//        terminal.println(specFile.readText())
//            terminal.println((minecraftYellow + bold)("Regex:"))
//            terminal.println("""((?<=\[server\.['"]?$targetServer['"]?\.(mods|plugins)\]\n)[^\[]*+(?=\[?))""")
//            terminal.println((minecraftYellow + bold)("Match:"))
//            terminal.println(tomlJarsRegex(targetServer).find(specFile.readText())?.value)
//            terminal.println((minecraftCyan + bold)("Which will produce:"))
//            terminal.println(
//                specFile.readText()
//                    .replace(tomlJarsRegex(targetServer)) { it.value.replace(Regex("""\s+(?=\s)""")) { "\n$tomlJars${it.value}" } })
            specFile.writeText(specFile.readText()
                .replace(tomlJarsRegex(targetServer)) { it.value.replace(Regex("""\s+(?=\s)""")) { "\n$tomlJars${it.value}" } })
            terminal.println((minecraftCyan + bold)("Jars added to spec (use build command to download them)"))
        } catch (e: Throwable) {
            println("${e::class.simpleName}: ${e.message}")
        }
    }
}

@Command(name = "upgrade", description = ["Upgrade all or some jars."])
object MidnightUpgradeCommand : Runnable {
    @Parameters(index = "0", description = ["Server for jars to be added"])
    lateinit var targetServer: String

    @Parameters(index = "1..*", description = ["Jar indentifiers. Supports any resolver defined in spec."])
    lateinit var toUpgrade: List<String>

    @Option(
        names = ["-f", "--file"],
        description = ["The spec.midnight.toml to use for building"],
        defaultValue = "./spec.midnight.toml"
    )
    var specFile: File = File("./spec.midnight.toml")

    @Option(names = ["--dry"], description = ["Only tells what is going to happen."])
    var dryRun = false
    @Option(names = ["--pin"], description = ["Pin stars (*) to specific version"])
    var pin = false
    override fun run() {
        TODO("Not yet implemented")
    }
}

fun jacksonMerge(main: JsonNode, other: JsonNode) {
    val fieldNames: Iterator<String> = other.fieldNames()
    while (fieldNames.hasNext()) {
        val fieldName = fieldNames.next()
        val jsonNode: JsonNode? = main.get(fieldName)
        // if field exists and is an embedded object
        if (jsonNode != null && jsonNode.isObject) {
            jacksonMerge(jsonNode, other.get(fieldName))
        } else {
            if (main is ObjectNode) {
                // Overwrite field
                val value: JsonNode = other.get(fieldName)
                main.replace(fieldName, value)
            }
        }
    }
}

fun ObjectNode.mergeWith(other: ObjectNode) = jacksonMerge(this, other)