package net.corda.plugins

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueFactory
import net.corda.cordform.CordformNode
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.gradle.api.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Represents a node that will be installed.
 */
class Node(private val project: Project) : CordformNode() {
    companion object {
        @JvmStatic
        val nodeJarName = "corda.jar"
        @JvmStatic
        val webJarName = "corda-webserver.jar"
        private val configFileProperty = "configFile"
        val capsuleCacheDir: String = "./cache"
    }

    fun fullPath(): Path = project.projectDir.toPath().resolve(nodeDir.toPath())
    fun logDirectory(): Path = fullPath().resolve("logs")
    fun makeLogDirectory() = Files.createDirectories(logDirectory())

    /**
     * Set the list of CorDapps to install to the plugins directory. Each cordapp is a fully qualified Maven
     * dependency name, eg: com.example:product-name:0.1
     *
     * @note Your app will be installed by default and does not need to be included here.
     * @note Type is any due to gradle's use of "GStrings" - each value will have "toString" called on it
     */
    var cordapps = mutableListOf<Any>()

    private val releaseVersion = project.rootProject.ext<String>("corda_release_version")
    internal lateinit var nodeDir: File
        private set

    /**
     * Sets whether this node will use HTTPS communication.
     *
     * @param isHttps True if this node uses HTTPS communication.
     */
    fun https(isHttps: Boolean) {
        config = config.withValue("useHTTPS", ConfigValueFactory.fromAnyRef(isHttps))
    }

    /**
     * Sets the H2 port for this node
     */
    fun h2Port(h2Port: Int) {
        config = config.withValue("h2port", ConfigValueFactory.fromAnyRef(h2Port))
    }

    fun useTestClock(useTestClock: Boolean) {
        config = config.withValue("useTestClock", ConfigValueFactory.fromAnyRef(useTestClock))
    }

    /**
     * Set the network map address for this node.
     *
     * @warning This should not be directly set unless you know what you are doing. Use the networkMapName in the
     *          Cordform task instead.
     * @param networkMapAddress Network map node address.
     * @param networkMapLegalName Network map node legal name.
     */
    fun networkMapAddress(networkMapAddress: String, networkMapLegalName: String) {
        val networkMapService = mutableMapOf<String, String>()
        networkMapService.put("address", networkMapAddress)
        networkMapService.put("legalName", networkMapLegalName)
        config = config.withValue("networkMapService", ConfigValueFactory.fromMap(networkMapService))
    }

    /**
     * Enables SSH access on given port
     *
     * @param sshdPort The port for SSH server to listen on
     */
    fun sshdPort(sshdPort: Int?) {
        config = config.withValue("sshd.port", ConfigValueFactory.fromAnyRef(sshdPort))
    }

    internal fun build() {
        configureProperties()
        installCordaJar()
        if (config.hasPath("webAddress")) {
            installWebserverJar()
        }
        installAgentJar()
        installBuiltCordapp()
        installCordapps()
        installConfig()
        appendOptionalConfig()
    }

    internal fun rootDir(rootDir: Path) {
        if (name == null) {
            project.logger.error("Node has a null name - cannot create node")
            throw IllegalStateException("Node has a null name - cannot create node")
        }

        val dirName = try {
            val o = X500Name(name).getRDNs(BCStyle.O)
            if (o.isNotEmpty()) o.first().first.value.toString() else name
        } catch (_ : IllegalArgumentException) {
            // Can't parse as an X500 name, use the full string
            name
        }
        nodeDir = File(rootDir.toFile(), dirName)
    }

    private fun configureProperties() {
        config = config.withValue("rpcUsers", ConfigValueFactory.fromIterable(rpcUsers))
        if (notary != null) {
            config = config.withValue("notary", ConfigValueFactory.fromMap(notary))
        }
        if (extraConfig != null) {
            config = config.withFallback(ConfigFactory.parseMap(extraConfig))
        }
    }

    /**
     * Installs the corda fat JAR to the node directory.
     */
    private fun installCordaJar() {
        val cordaJar = verifyAndGetCordaJar()
        project.copy {
            it.apply {
                from(cordaJar)
                into(nodeDir)
                rename(cordaJar.name, nodeJarName)
                fileMode = Cordformation.executableFileMode
            }
        }
    }

    /**
     * Installs the corda webserver JAR to the node directory
     */
    private fun installWebserverJar() {
        val webJar = verifyAndGetWebserverJar()
        project.copy {
            it.apply {
                from(webJar)
                into(nodeDir)
                rename(webJar.name, webJarName)
            }
        }
    }

    /**
     * Installs this project's cordapp to this directory.
     */
    private fun installBuiltCordapp() {
        val cordappsDir = File(nodeDir, "cordapps")
        project.copy {
            it.apply {
                from(project.tasks.getByName("jar"))
                into(cordappsDir)
            }
        }
    }

    /**
     * Installs other cordapps to this node's cordapps directory.
     */
    internal fun installCordapps(cordapps: Collection<File> = getCordappList()) {
        val cordappsDir = File(nodeDir, "cordapps")
        project.copy {
            it.apply {
                from(cordapps)
                into(cordappsDir)
            }
        }
    }

    /**
     * Installs the jolokia monitoring agent JAR to the node/drivers directory
     */
    private fun installAgentJar() {
        val jolokiaVersion = project.rootProject.ext<String>("jolokia_version")
        val agentJar = project.configuration("runtime").files {
            (it.group == "org.jolokia") &&
            (it.name == "jolokia-jvm") &&
            (it.version == jolokiaVersion)
            // TODO: revisit when classifier attribute is added. eg && (it.classifier = "agent")
        }.first()  // should always be the jolokia agent fat jar: eg. jolokia-jvm-1.3.7-agent.jar
        project.logger.info("Jolokia agent jar: $agentJar")
        if (agentJar.isFile) {
            val driversDir = File(nodeDir, "drivers")
            project.copy {
                it.apply {
                    from(agentJar)
                    into(driversDir)
                }
            }
        }
    }

    /**
     * Installs the configuration file to this node's directory and detokenises it.
     */
    private fun installConfig() {
        val options = ConfigRenderOptions
                .defaults()
                .setOriginComments(false)
                .setComments(false)
                .setFormatted(true)
                .setJson(false)
        val configFileText = config.root().render(options).split("\n").toList()

        // Need to write a temporary file first to use the project.copy, which resolves directories correctly.
        val tmpDir = File(project.buildDir, "tmp")
        val tmpConfFile = File(tmpDir, "node.conf")
        Files.write(tmpConfFile.toPath(), configFileText, StandardCharsets.UTF_8)

        project.copy {
            it.apply {
                from(tmpConfFile)
                into(nodeDir)
            }
        }
    }

    /**
     * Appends installed config file with properties from an optional file.
     */
    private fun appendOptionalConfig() {
        val optionalConfig: File? = when {
            project.findProperty(configFileProperty) != null -> //provided by -PconfigFile command line property when running Gradle task
                File(project.findProperty(configFileProperty) as String)
            config.hasPath(configFileProperty) -> File(config.getString(configFileProperty))
            else -> null
        }

        if (optionalConfig != null) {
            if (!optionalConfig.exists()) {
                project.logger.error("$configFileProperty '$optionalConfig' not found")
            } else {
                val confFile = File(project.buildDir.path + "/../" + nodeDir, "node.conf")
                confFile.appendBytes(optionalConfig.readBytes())
            }
        }
    }

    /**
     * Find the corda JAR amongst the dependencies.
     *
     * @return A file representing the Corda JAR.
     */
    private fun verifyAndGetCordaJar(): File {
        val maybeCordaJAR = project.configuration("runtime").filter {
            it.toString().contains("corda-$releaseVersion.jar") || it.toString().contains("corda-enterprise-$releaseVersion.jar")
        }
        if (maybeCordaJAR.isEmpty) {
            throw RuntimeException("No Corda Capsule JAR found. Have you deployed the Corda project to Maven? Looked for \"corda-$releaseVersion.jar\"")
        } else {
            val cordaJar = maybeCordaJAR.singleFile
            assert(cordaJar.isFile)
            return cordaJar
        }
    }

    /**
     * Find the corda JAR amongst the dependencies
     *
     * @return A file representing the Corda webserver JAR
     */
    private fun verifyAndGetWebserverJar(): File {
        val maybeJar = project.configuration("runtime").filter {
            it.toString().contains("corda-webserver-$releaseVersion.jar")
        }
        if (maybeJar.isEmpty) {
            throw RuntimeException("No Corda Webserver JAR found. Have you deployed the Corda project to Maven? Looked for \"corda-webserver-$releaseVersion.jar\"")
        } else {
            val jar = maybeJar.singleFile
            require(jar.isFile)
            return jar
        }
    }

    /**
     * Gets a list of cordapps based on what dependent cordapps were specified.
     *
     * @return List of this node's cordapps.
     */
    private fun getCordappList(): Collection<File> {
        // Cordapps can sometimes contain a GString instance which fails the equality test with the Java string
        @Suppress("RemoveRedundantCallsOfConversionMethods")
        val cordapps: List<String> = cordapps.map { it.toString() }
        return project.configuration("cordapp").files {
            cordapps.contains(it.group + ":" + it.name + ":" + it.version)
        }
    }
}
