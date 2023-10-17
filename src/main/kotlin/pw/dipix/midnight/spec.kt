package pw.dipix.midnight

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.ajalt.mordant.rendering.TextStyles.bold
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.util.*

val defaultServerProperies =
    Properties().apply { load(MidnightSpecification::class.java.getResourceAsStream("/server.properties")) }

data class MidnightSpecification(
    val general: MidnightSpecificationGeneralConf,
    @JsonProperty("server")
    val servers: Map<String, MidnightSpecificationServer>
) {
    val resolved: Boolean get() = servers.values.first().resolved
    fun toResolved() = copy(servers = servers.mapValues { it.value.copy(specification = this, name = it.key) })
    val rootServer = servers.values.firstOrNull { it.parent == null }

    fun buildDockerCompose(): ObjectNode {
        if (!resolved) throw IllegalStateException("Cannot build docker compose file from unresolved spec.")
        val root = jacksonYamlMapper.createObjectNode()
        root.put("version", "3.8")
        val services = root.putObject("services")
        servers.map { it.key to it.value.toItzgService(jacksonYamlMapper) }
            .forEach { services.replace(it.first, it.second) }
        return root
    }

//    suspend fun downloadJars(): Map<String, File> {
//        return servers.map { it.value.downloadJars() }.fold(mapOf()) { acc, map -> acc + (map ?: mapOf()) }
//    }
}

data class MidnightSpecificationGeneralConf(
    val port: Short,
    @JsonProperty("jar-source-order")
    val jarSourceOrder: List<String>,
    @JsonProperty("data-dir")
    val dataDir: File,
    @JsonProperty("config-dir")
    val configDir: File,
    @JsonProperty("overlay-dir")
    val overlayDir: File,
)

class MidnightSpecificationServer(
    val type: String,
    val version: String,
    val children: List<String>? = null,
    val plugins: Map<String, String>? = null,
    val mods: Map<String, String>? = null,
    val properties: ObjectNode? = null,
    @JsonProperty("data-dir")
    dataDir: File? = null,
    @JsonProperty("config-dir")
    configDir: File? = null,
    @JsonProperty("overlay-dir")
    overlayDir: File? = null,
    @JsonIgnore
    val specification: MidnightSpecification? = null,
    @JsonIgnore
    val name: String? = null,
) {
    val dataDir: File? = dataDir ?: this.specification?.general?.dataDir?.resolve("$name")
    val configDir: File? = configDir ?: this.specification?.general?.configDir?.resolve("$name")
    val overlayDir: File? = overlayDir ?: this.specification?.general?.overlayDir?.resolve("$name")
    val isProxy: Boolean get() = listOf("velocity", "waterfall", "bungeecord").contains(type) // TODO: add all supported
    val isModded: Boolean get() = listOf("fabric", "forge").contains(type) // TODO: add all supported
    val isPluginServer: Boolean get() = listOf("spigot", "paper").contains(type) // TODO: add all supported
    val isPluginModded: Boolean get() = isPluginServer || isProxy
    val defaultPort get() = if (type == "velocity") 25577 else 25565
    val parent: MidnightSpecificationServer?
        get() = specification?.servers?.values?.firstOrNull {
            it.children?.contains(
                name
            ) == true
        }
    val resolved: Boolean get() = name != null && specification != null
    val minecraftVersion: String? get() = if (isProxy) null else if (!isModded) version else version.split(":")[1]

    /** Server type version (fabric version, velocity version, etc....) */
    val softwareVersion: String? get() = if (!isProxy && !isModded) null else if (isProxy) version else version.split(":")[0]

    fun toItzgService(mapper: ObjectMapper): ObjectNode = if (isProxy) toItzgProxy(mapper) else toItzgMinecraft(mapper)

    private fun toItzgMinecraft(mapper: ObjectMapper): ObjectNode {
        if (!resolved) throw IllegalStateException("Cannot convert unresolved server to itzg")
        val service = mapper.createObjectNode()
        service.put("image", "itzg/minecraft-server")
        service.put("tty", true)
        service.put("stdin_open", true)
        if (parent == null) service.putArray("ports").add("${specification!!.general.port}:$defaultPort")
        service.putArray("volumes").add("$dataDir:/data")
        val env = service.putObject("environment")
        env.put("EULA", "TRUE")
        env.put("TYPE", type.uppercase())
        if (minecraftVersion != null) env.put("VERSION", minecraftVersion)
        if (softwareVersion != null && !isPluginModded) {
            if (type == "fabric") {
                env.put("FABRIC_LOADER_VERSION", softwareVersion)
            } else {
                env.put("${type.uppercase()}_VERSION", softwareVersion)
            }
        }
        return service
    }

    private fun toItzgProxy(mapper: ObjectMapper): ObjectNode {
        if (!resolved) throw IllegalStateException("Cannot convert unresolved server to itzg")
        val service = mapper.createObjectNode()
        service.put("image", "itzg/bungeecord") // don't be fooled - it supports velocity
        service.put("tty", true)
        service.put("stdin_open", true)
        if (parent == null) service.putArray("ports").add("${specification!!.general.port}:$defaultPort")
        service.putArray("volumes").add("$dataDir:/data")
        val env = service.putObject("environment")
//        env.put("EULA", "TRUE")
        env.put("TYPE", type.uppercase())
        if (softwareVersion != null) env.put("${type.uppercase()}_VERSION", softwareVersion)
        return service
    }

    fun copy(
        type: String = this.type,
        version: String = this.version,
        children: List<String>? = this.children,
        plugins: Map<String, String>? = this.plugins,
        mods: Map<String, String>? = this.mods,
        properties: ObjectNode? = this.properties,
        dataDir: File? = this.dataDir,
        configDir: File? = this.configDir,
        overlayDir: File? = this.overlayDir,
        specification: MidnightSpecification? = this.specification,
        name: String? = this.name,
    ) = MidnightSpecificationServer(
        type,
        version,
        children,
        plugins,
        mods,
        properties,
        dataDir,
        configDir,
        overlayDir,
        specification,
        name
    )

    suspend fun downloadJars(): Map<String, File>? {
        terminal.println((bold + minecraftCyan)("Server $name:"))
        val jars = if (this.isModded) this.mods else if (this.isPluginModded) this.plugins else null
        val folder =
            (if (this.isModded) "mods" else if (this.isPluginModded) "plugins" else null)?.let { File(it) }
        val parsedJars = jars?.mapValues {
            MidnightJarSource.parseAndGetJar(it.key, it.value, this)
                ?: throw RuntimeException("${it.key} did not resolve.")
        }
        val tasks = parsedJars?.map {
            Downloadable.of(it.value.second).toTask(folder!!.resolve("${it.value.first}.jar")) { finished, total ->
                if (finished == total) terminal.println((bold + minecraftGreen)("Downloaded ${it.value.first}.jar"))
            }
        }
//            println("Tasks: $tasks")
        runBlocking { tasks?.downloadAll() }
        if (tasks?.isEmpty() == true) {
            terminal.println((bold + minecraftGreen)("No tasks defined."))
        }
        return parsedJars?.mapValues { folder!!.resolve("${it.value.first}.jar") }
    }
}

interface MidnightJarSource {

    /** Resolve mod download url. Returns null if cannot be resolved (doesn't exist, error, etc...) */
    fun resolve(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String = "*"
    ): Pair<String, URL>?

    /** If whatever name given is supported by source. Modrinth supports its slugs, github supports user/repo, etc... */
    fun supportsDownload(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String = "*"
    ): Boolean = false

    companion object : MidnightJarSource {
        val sources = mutableMapOf(
            "modrinth" to ModrinthApi,
            "github" to GithubApi,
            "direct" to DirectURLJarSource,
            "local" to LocalJarSource
        )

        fun parseAndGetJar(key: String, value: String, server: MidnightSpecificationServer): Pair<String, URL>? =
            resolve(key, server, value)

        override fun supportsDownload(
            name: String,
            server: MidnightSpecificationServer,
            modVersion: String
        ): Boolean = sources.map { it.value.supportsDownload(name, server, modVersion) }.any { it }

        override fun resolve(
            name: String,
            server: MidnightSpecificationServer,
            modVersion: String
        ): Pair<String, URL>? {
            terminal.println((minecraftLightPurple + bold)("Resolving $name..."))
            return server.specification!!.general.jarSourceOrder.map { sources[it] }
                .firstOrNull { it?.supportsDownload(name, server, modVersion) == true }
                ?.resolve(name, server, modVersion)
        }
    }
}

object ModrinthApi : MidnightJarSource {
    const val ENDPOINT = "https://api.modrinth.com"
    const val VERSION = "v2"

    fun apiUrl(string: String) = URL("$ENDPOINT/$VERSION$string")
    override fun resolve(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Pair<String, URL>? {
        val compatibleVersions =
            runBlocking {
                val response =
                    httpClient.get(apiUrl("/project/$name/version?loaders=[%22${server.type}%22]${server.minecraftVersion?.let { "&game_versions=[%22$it%22]" } ?: ""}"))
                try {
                    response.body<ArrayNode>()
                } catch (e: Throwable) {
                    println("ModrinthApi: ${e.message}")
                    null
                }
            }
//        println(compatibleVersions?.toPrettyString())
        return compatibleVersions?.firstOrNull { if (modVersion == "*") true else (it["version_number"].asText() == modVersion) }
            ?.get("files")?.maxByOrNull { it["primary"].asBoolean() }?.get("url")?.asText()?.let { URL(it) }
            ?.let { name to it }
    }

    override fun supportsDownload(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Boolean {
        val modrinthSlugRegex = Regex("""^[\w!@$()`.+,"\-']{3,64}$""", RegexOption.IGNORE_CASE)
        return name.matches(modrinthSlugRegex)
    }
}

object GithubApi : MidnightJarSource {
    val githubUserRegex = Regex("""^[a-z\d](?:[a-z\d]|-(?=[a-z\d])){0,38}$""", RegexOption.IGNORE_CASE)
    val githubRepoRegex = Regex("""^[a-z\d-_.]+$""", RegexOption.IGNORE_CASE)
    val githubModName = Regex("""^(?<user>[^/]*)/(?<repo>.*)$""", RegexOption.IGNORE_CASE)
    val githubTagAndFile = Regex("""^(?<tag>[^/]*)(/(?<file>.*))?${'$'}""", RegexOption.IGNORE_CASE)
    const val ENDPOINT = "https://api.github.com"

    fun apiUrl(string: String) = URL("$ENDPOINT$string")
    override fun resolve(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Pair<String, URL>? {
        val githubToken = tokens.getProperty("github")
        val user = githubModName.matchEntire(name)?.groups?.get("user")?.value!!
        val repoName = githubModName.matchEntire(name)?.groups?.get("repo")?.value!!
        val tag = githubTagAndFile.matchEntire(modVersion)?.groups?.get("tag")?.value!!
        val file = githubTagAndFile.matchEntire(modVersion)?.groups?.get("file")?.value
        val assetsUrl = try {
            runBlocking {
                httpClient.get(apiUrl(if (tag == "*") "/repos/$user/$repoName/releases/latest" else "/repos/$user/$repoName/releases/tags/$tag")) {
                    if (githubToken != null) bearerAuth(githubToken)
                }
                    .apply { bodyAsText().run { println(this) } }
                    .body<ObjectNode>()["assets_url"].asText()
            }
        } catch (e: Throwable) {
            println("GithubApi: ${e.message}")
            null
        } ?: return null
        val assets = runBlocking {
            httpClient.get(URL(assetsUrl)) { if (githubToken != null) bearerAuth(githubToken) }.body<ArrayNode>()
        }
        return repoName to URL(assets.firstOrNull {
            it["name"].asText()
                .let { assetName -> if (file == null) assetName.matches(Regex("""(.*).jar$""")) else assetName == file }
        }?.get("url")?.asText())
    }

    override fun supportsDownload(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Boolean {
        return name.matches(githubModName) && githubModName.matchEntire(name)?.groups?.get("user")?.value?.matches(
            githubUserRegex
        ) == true && githubModName.matchEntire(name)?.groups?.get("repo")?.value?.matches(githubRepoRegex) == true
    }
}

object DirectURLJarSource : MidnightJarSource {
    override fun resolve(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Pair<String, URL> = name to URL(modVersion)

    override fun supportsDownload(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Boolean = try {
        URL(modVersion); true
    } catch (e: MalformedURLException) {
        false
    }
}

object LocalJarSource : MidnightJarSource {
    override fun resolve(name: String, server: MidnightSpecificationServer, modVersion: String): Pair<String, URL>? {
        terminal.println((minecraftYellow + bold)("Local file resolved for $name (${File(modVersion).absolutePath}). Note that local files break the principle of minimal config, and you should try to find a better way to do this."))
        return if (File(modVersion).exists()) name to File(modVersion).toURI().toURL() else null
    }

    override fun supportsDownload(name: String, server: MidnightSpecificationServer, modVersion: String): Boolean {
//        terminal.println((minecraftYellow + bold)(modVersion))
//        terminal.println((minecraftYellow + bold)(File(modVersion).absolutePath))
        return File(modVersion).exists()
    }
}