package net.corda.testing.driver

import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.*
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.minutes
import net.corda.core.utilities.seconds
import net.corda.node.internal.NodeStartup
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.getX509Certificate
import net.corda.nodeapi.internal.crypto.loadOrCreateKeyStore
import net.corda.testing.DUMMY_BANK_A
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.DUMMY_REGULATOR
import net.corda.testing.common.internal.ProjectStructure.projectRootDir
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.network.NetworkMapServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.ok

class DriverTests {
    companion object {
        private val executorService: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

        private fun nodeMustBeUp(handleFuture: CordaFuture<out NodeHandle>) = handleFuture.getOrThrow().apply {
            val hostAndPort = nodeInfo.addresses.first()
            // Check that the port is bound
            addressMustBeBound(executorService, hostAndPort, (this as? NodeHandle.OutOfProcess)?.process)
        }

        private fun nodeMustBeDown(handle: NodeHandle) {
            val hostAndPort = handle.nodeInfo.addresses.first()
            // Check that the port is bound
            addressMustNotBeBound(executorService, hostAndPort)
        }
    }
    private val portAllocation = PortAllocation.Incremental(10000)

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `simple node startup and shutdown`() {
        val handle = driver {
            val regulator = startNode(providedName = DUMMY_REGULATOR.name)
            nodeMustBeUp(regulator)
        }
        nodeMustBeDown(handle)
    }

    @Test
    fun `random free port allocation`() {
        val nodeHandle = driver(portAllocation = portAllocation) {
            val nodeInfo = startNode(providedName = DUMMY_BANK_A.name)
            nodeMustBeUp(nodeInfo)
        }
        nodeMustBeDown(nodeHandle)
    }

    @Test
    fun `node registration`() {
        val handler = RegistrationHandler()
        val keystorePath = tempFolder.root.toPath() / "keystore.jks"
        javaClass.classLoader.getResourceAsStream("certificates/cordatruststore.jks").copyTo(keystorePath)
        val keyStore = loadOrCreateKeyStore(keystorePath, "trustpass")
        val rootCert = keyStore.getX509Certificate(X509Utilities.CORDA_ROOT_CA)

        NetworkMapServer(1.seconds, portAllocation.nextHostAndPort(), handler).use {
            val (host, port) = it.start()
            internalDriver(portAllocation = portAllocation,
                    compatibilityZone = CompatibilityZoneParams(URL("http://$host:$port"), rootCert = rootCert)) {
                // Wait for the node to have started.
                notaryHandles[0].nodeHandles.get()
            }
        }
        // We're getting:
        //   a request to sign the certificate then
        //   at least one poll request to see if the request has been approved.
        //   all the network map registration and download.
        assertThat(handler.requests).startsWith("/certificate", "/certificate/reply")
    }

    @Test
    fun `debug mode enables debug logging level`() {
        // Make sure we're using the log4j2 config which writes to the log file
        val logConfigFile = projectRootDir / "config" / "dev" / "log4j2.xml"
        assertThat(logConfigFile).isRegularFile()
        driver(isDebug = true, systemProperties = mapOf("log4j.configurationFile" to logConfigFile.toString())) {
            val baseDirectory = startNode(providedName = DUMMY_BANK_A.name).getOrThrow().configuration.baseDirectory
            val logFile = (baseDirectory / NodeStartup.LOGS_DIRECTORY_NAME).list { it.sorted().findFirst().get() }
            val debugLinesPresent = logFile.readLines { lines -> lines.anyMatch { line -> line.startsWith("[DEBUG]") } }
            assertThat(debugLinesPresent).isTrue()
        }
    }

    @Test
    fun `started node, which is not waited for in the driver, is shutdown when the driver exits`() {
        // First check that the process-id file is created by the node on startup, so that we can be sure our check that
        // it's deleted on shutdown isn't a false-positive.
        driver {
            val baseDirectory = defaultNotaryNode.getOrThrow().configuration.baseDirectory
            assertThat(baseDirectory / "process-id").exists()
        }

        val baseDirectory = internalDriver(notarySpecs = listOf(NotarySpec(DUMMY_NOTARY.name))) {
            baseDirectory(DUMMY_NOTARY.name)
        }
        assertThat(baseDirectory / "process-id").doesNotExist()
    }
}

@Path("certificate")
class RegistrationHandler {
    val requests = mutableListOf<String>()
    @POST
    fun registration(): Response {
        requests += "/certificate"
        return ok("reply").build()
    }

    @GET
    @Path("reply")
    fun reply(): Response {
        requests += "/certificate/reply"
        return ok().build()
    }
}
