package pw.dipix.midnight

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.File
import java.net.MalformedURLException
import java.net.URL

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
        val root = yamlMapper.createObjectNode()
        root.put("version", "3.8")
        val services = root.putObject("services")
        servers.map { it.key to it.value.toItzgService(yamlMapper) }.forEach { services.replace(it.first, it.second) }
        return root
    }
}

data class MidnightSpecificationGeneralConf(
    val port: Short,
    @JsonProperty("jar-source-order")
    val jarSourceOrder: List<String>,
    @JsonProperty("data-dir")
    val dataDir: File,
    @JsonProperty("config-dir")
    val configDir: File,
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
    @JsonIgnore
    val specification: MidnightSpecification? = null,
    @JsonIgnore
    val name: String? = null,
) {
    val dataDir: File? = dataDir ?: this.specification?.general?.dataDir?.resolve("$name")
    val configDir: File? = configDir ?: this.specification?.general?.configDir?.resolve("$name")
    val isProxy: Boolean get() = listOf("velocity", "waterfall", "bungeecord").contains(type)
    val isModded: Boolean get() = listOf("fabric", "forge").contains(type) // TODO: add all supported
    val isPluginModded: Boolean get() = listOf("spigot", "paper").contains(type) || isProxy // TODO: add all supported
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
        if (parent == null) service.putArray("ports").add("${specification!!.general.port}:25565")
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
        if (parent == null) service.putArray("ports").add("${specification!!.general.port}:25565")
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
        specification,
        name
    )
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
        val sources = mutableMapOf("modrinth" to ModrinthApi, "github" to GithubApi, "direct" to DirectURLJarSource)
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
//            val githubUserRegex = Regex("""^[a-z\d](?:[a-z\d]|-(?=[a-z\d])){0,38}$""", RegexOption.IGNORE_CASE)
//            val githubRepoRegex = Regex("""^[a-z\d-_.]+$""", RegexOption.IGNORE_CASE)
//            val githubModName = Regex("""^(?<user>.*)/(?<repo>.*)$""", RegexOption.IGNORE_CASE)
//            val modrinthSlugRegex = Regex("""^[\w!@$()`.+,"\-']{3,64}$""", RegexOption.IGNORE_CASE)
//            when {
//                try {
//                    URL(modVersion); true
//                } catch (e: MalformedURLException) {
//                    false
//                } -> URL(modVersion)
//
//                name.matches(githubModName) && githubModName.matchEntire(name)?.groups?.get("user")?.value?.matches(
//                    githubUserRegex
//                ) == true && githubModName.matchEntire(name)?.groups?.get("repo")?.value?.matches(githubRepoRegex) == true -> {
//                    val (user, repo) = githubModName.matchEntire(name)?.groups?.run {
//                        arrayOf(
//                            get("user")?.value!!,
//                            get("repo")?.value!!
//                        )
//                    }!!
//                    null
//                }
//
//                name.matches(modrinthSlugRegex) -> {
//                    ModrinthApi.resolveToDownload(name, softwareType, minecraftVersion, modVersion)
//                }
//
//                else -> TODO("Implement download for: $name = $modVersion")
//            }
            val download = server.specification!!.general.jarSourceOrder.map { sources[it] }
                .firstOrNull { it?.supportsDownload(name, server, modVersion) == true }
                ?.resolve(name, server, modVersion)
            return download
        }
    }
}

object ModrinthApi : MidnightJarSource {
    val endpoint = "https://api.modrinth.com"
    val version = "v2"

    fun apiUrl(string: String) = URL("$endpoint/$version$string")
    override fun resolve(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Pair<String, URL>? {
        val compatibleVersions =
            runBlocking {
                val response =
                    httpClient.get(apiUrl("/project/$name/version?loaders=[%22${server.type}%22]${server.minecraftVersion?.let { "&game_versions=[%22$it%22]" } ?: ""}")) {
                        accept(ContentType.Application.Json)
                    }
                try {
                    response.body<ArrayNode>()
                } catch (e: Throwable) {
                    println("ModrinthApi: ${e.message}")
                    null
                }
            }
//        println(compatibleVersions?.toPrettyString())
        return compatibleVersions?.firstOrNull { if (modVersion == "*") true else (it["version_number"].asText() == modVersion) }
            ?.get("files")
            ?.first { it["primary"].asBoolean() }?.get("url")?.asText()?.let { URL(it) }?.let { name to it }
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
    val githubModName = Regex("""^(?<user>.*)/(?<repo>.*)$""", RegexOption.IGNORE_CASE)
    val githubTagAndFile = Regex("""^(?<tag>.*)(/(?<file>.*))?$""", RegexOption.IGNORE_CASE)
    val endpoint = "https://api.github.com"

    fun apiUrl(string: String) = URL("$endpoint$string")
    override fun resolve(
        name: String,
        server: MidnightSpecificationServer,
        modVersion: String
    ): Pair<String, URL>? {
        val user = githubModName.matchEntire(name)?.groups?.get("user")?.value!!
        val repoName = githubModName.matchEntire(name)?.groups?.get("repo")?.value!!
        val tag = githubTagAndFile.matchEntire(modVersion)?.groups?.get("tag")?.value!!
        val file = githubTagAndFile.matchEntire(modVersion)?.groups?.get("file")?.value
        val assetsUrl = runBlocking {
            httpClient.get(apiUrl(if (tag == "*") "/repos/$user/$repoName/releases/latest" else "/repos/$user/$repoName/releases/tags/$modVersion")) {
                accept(ContentType.Application.Json)
            }.body<ObjectNode>()["assets_url"].asText()
        }
        val assets = runBlocking {
            httpClient.get(URL(assetsUrl)) {
                accept(ContentType.Application.Json)
            }.body<ArrayNode>()
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
    ): Pair<String, URL>? = name to URL(modVersion)

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

