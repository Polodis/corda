package net.corda.node.amqp

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.div
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.toFuture
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.protonwrapper.messages.MessageStatus
import net.corda.node.internal.protonwrapper.netty.AMQPClient
import net.corda.node.internal.protonwrapper.netty.AMQPServer
import net.corda.node.services.RPCUserService
import net.corda.node.services.config.configureWithDevSSLCertificate
import net.corda.node.services.messaging.ArtemisMessagingClient
import net.corda.node.services.messaging.ArtemisMessagingServer
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.PEER_USER
import net.corda.nodeapi.internal.crypto.loadKeyStore
import net.corda.testing.*
import org.apache.activemq.artemis.api.core.RoutingType
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rx.Observable.never
import kotlin.test.assertEquals

class ProtonWrapperTests {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val serverPort = freePort()
    private val serverPort2 = freePort()
    private val artemisPort = freePort()

    @Test
    fun `Simple AMPQ Client to Server`() {
        val amqpServer = createServer(serverPort)
        amqpServer.use {
            amqpServer.start()
            val receiveSubs = amqpServer.onReceive.subscribe {
                assertEquals(BOB.name.toString(), it.sourceLegalName)
                assertEquals("p2p.inbound", it.topic)
                assertEquals("Test", String(it.payload))
                it.complete(true)
            }
            val amqpClient = createClient()
            amqpClient.use {
                val serverConnected = amqpServer.onConnection.toFuture()
                val clientConnected = amqpClient.onConnection.toFuture()
                amqpClient.start()
                val serverConnect = serverConnected.get()
                assertEquals(true, serverConnect.connected)
                assertEquals(BOB.name, CordaX500Name.parse(serverConnect.remoteCert!!.subject.toString()))
                val clientConnect = clientConnected.get()
                assertEquals(true, clientConnect.connected)
                assertEquals(ALICE.name, CordaX500Name.parse(clientConnect.remoteCert!!.subject.toString()))
                val msg = amqpClient.createMessage("Test".toByteArray(),
                        "p2p.inbound",
                        ALICE.name.toString(),
                        emptyMap())
                amqpClient.write(msg)
                assertEquals(MessageStatus.Acknowledged, msg.onComplete.get())
                receiveSubs.unsubscribe()
            }
        }
    }

    @Test
    fun `Client Failover for multiple IP`() {
        val amqpServer = createServer(serverPort)
        val amqpServer2 = createServer(serverPort2)
        val amqpClient = createClient()
        try {
            val serverConnected = amqpServer.onConnection.toFuture()
            val serverConnected2 = amqpServer2.onConnection.toFuture()
            val clientConnected = amqpClient.onConnection.toBlocking().iterator
            amqpServer.start()
            amqpClient.start()
            val serverConn1 = serverConnected.get()
            assertEquals(true, serverConn1.connected)
            assertEquals(BOB.name, CordaX500Name.parse(serverConn1.remoteCert!!.subject.toString()))
            val connState1 = clientConnected.next()
            assertEquals(true, connState1.connected)
            assertEquals(ALICE.name, CordaX500Name.parse(connState1.remoteCert!!.subject.toString()))
            assertEquals(serverPort, connState1.remoteAddress.port)

            // Fail over
            amqpServer2.start()
            amqpServer.stop()
            val connState2 = clientConnected.next()
            assertEquals(false, connState2.connected)
            assertEquals(serverPort, connState2.remoteAddress.port)
            val serverConn2 = serverConnected2.get()
            assertEquals(true, serverConn2.connected)
            assertEquals(BOB.name, CordaX500Name.parse(serverConn2.remoteCert!!.subject.toString()))
            val connState3 = clientConnected.next()
            assertEquals(true, connState3.connected)
            assertEquals(ALICE.name, CordaX500Name.parse(connState3.remoteCert!!.subject.toString()))
            assertEquals(serverPort2, connState3.remoteAddress.port)

            // Fail back
            amqpServer.start()
            amqpServer2.stop()
            val connState4 = clientConnected.next()
            assertEquals(false, connState4.connected)
            assertEquals(serverPort2, connState4.remoteAddress.port)
            val serverConn3 = serverConnected.get()
            assertEquals(true, serverConn3.connected)
            assertEquals(BOB.name, CordaX500Name.parse(serverConn3.remoteCert!!.subject.toString()))
            val connState5 = clientConnected.next()
            assertEquals(true, connState5.connected)
            assertEquals(ALICE.name, CordaX500Name.parse(connState5.remoteCert!!.subject.toString()))
            assertEquals(serverPort, connState5.remoteAddress.port)
        } finally {
            amqpClient.close()
            amqpServer.close()
            amqpServer2.close()
        }
    }

    @Test
    fun `Send a message from AMQP to Artemis inbox`() {
        val (server, artemisClient) = createArtemisServerAndClient()
        val amqpClient = createClient()
        val clientConnected = amqpClient.onConnection.toFuture()
        amqpClient.start()
        assertEquals(true, clientConnected.get().connected)
        assertEquals(CHARLIE.name, CordaX500Name.parse(clientConnected.get().remoteCert!!.subject.toString()))
        val artemis = artemisClient.started!!
        val sendAddress = "p2p.inbound"
        artemis.session.createQueue(sendAddress, RoutingType.MULTICAST, "queue", true)
        val consumer = artemis.session.createConsumer("queue")
        val testData = "Test".toByteArray()
        val testProperty = mutableMapOf<Any?, Any?>()
        testProperty["TestProp"] = "1"
        val message = amqpClient.createMessage(testData, sendAddress, CHARLIE.name.toString(), testProperty)
        amqpClient.write(message)
        assertEquals(MessageStatus.Acknowledged, message.onComplete.get())
        val received = consumer.receive()
        assertEquals("1", received.getStringProperty("TestProp"))
        assertArrayEquals(testData, ByteArray(received.bodySize).apply { received.bodyBuffer.readBytes(this) })
        amqpClient.stop()
        artemisClient.stop()
        server.stop()
    }

    private fun createArtemisServerAndClient(): Pair<ArtemisMessagingServer, ArtemisMessagingClient> {
        val artemisConfig = testNodeConfiguration(
                baseDirectory = temporaryFolder.root.toPath() / "artemis",
                myLegalName = CHARLIE.name)
        artemisConfig.configureWithDevSSLCertificate()
        val networkMap = rigorousMock<NetworkMapCache>().also {
            doReturn(never<NetworkMapCache.MapChange>()).whenever(it).changed
        }
        val userService = rigorousMock<RPCUserService>().also {

        }
        val server = ArtemisMessagingServer(artemisConfig, artemisPort, null, networkMap, userService)
        val client = ArtemisMessagingClient(artemisConfig, NetworkHostAndPort("localhost", artemisPort))
        server.start()
        client.start()
        return Pair(server, client)
    }

    private fun createClient(): AMQPClient {
        val clientConfig = testNodeConfiguration(
                baseDirectory = temporaryFolder.root.toPath() / "client",
                myLegalName = BOB.name)
        clientConfig.configureWithDevSSLCertificate()

        val clientTruststore = loadKeyStore(clientConfig.trustStoreFile, clientConfig.trustStorePassword)
        val clientKeystore = loadKeyStore(clientConfig.sslKeystore, clientConfig.keyStorePassword)
        val amqpClient = AMQPClient(listOf(NetworkHostAndPort("localhost", serverPort),
                NetworkHostAndPort("localhost", serverPort2),
                NetworkHostAndPort("localhost", artemisPort)),
                setOf(ALICE.name, CHARLIE.name),
                PEER_USER,
                PEER_USER,
                clientKeystore,
                clientConfig.keyStorePassword,
                clientTruststore, true)
        return amqpClient
    }

    private fun createServer(port: Int): AMQPServer {
        val serverConfig = testNodeConfiguration(
                baseDirectory = temporaryFolder.root.toPath() / "server",
                myLegalName = ALICE.name)
        serverConfig.configureWithDevSSLCertificate()

        val serverTruststore = loadKeyStore(serverConfig.trustStoreFile, serverConfig.trustStorePassword)
        val serverKeystore = loadKeyStore(serverConfig.sslKeystore, serverConfig.keyStorePassword)
        val amqpServer = AMQPServer("0.0.0.0",
                port,
                PEER_USER,
                PEER_USER,
                serverKeystore,
                serverConfig.keyStorePassword,
                serverTruststore)
        return amqpServer
    }
}