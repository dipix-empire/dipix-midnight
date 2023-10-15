package pw.dipix.midnight


// some dockerfile building shenanigans. i remembered minecraft docker containers were made already so why bother
class DockerfileBuilder {
    private val stringBuilder = StringBuilder()

    fun from(image: String) {
        stringBuilder.append("FROM ${image}\n")
    }

    /**
     * Converts string to single run operation.
     */
    fun run(script: String) {
        val runBuilder = StringBuilder()
        script.lines().forEachIndexed { i, it ->
            if (it.trim().endsWith("\\")) {
                runBuilder.append("${it.trim().removeSuffix("\\").trim()} ")
            } else {
                runBuilder.append("${it.trim()} && \\\n")
            }
        }
        stringBuilder.append("RUN ${runBuilder.removeSuffix(" && \\\n")}")
    }

    fun copy(sources: List<String>, destination: String, chown: String? = null) {
        stringBuilder.append("COPY ")
        if(chown != null) stringBuilder.append("--chown=$chown ")
        stringBuilder.append(sources.joinToString(" ", postfix = " "))
        stringBuilder.append(destination)
    }

    fun entryPoint(cmd: String) {
        cmd.split(" ").joinToString(", ", prefix = "[", postfix = "]") { "\"$it\"" }
            .run { stringBuilder.append("ENTRYPOINT $this") }
    }

    fun cmd(cmd: String) {
        cmd.split(" ").joinToString(", ", prefix = "[", postfix = "]") { "\"$it\"" }
            .run { stringBuilder.append("CMD $this") }
    }

    fun build() = stringBuilder.toString()
}