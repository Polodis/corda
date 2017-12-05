package net.corda.notarydemo

import net.corda.cordform.CordformContext
import net.corda.cordform.CordformDefinition
import net.corda.node.services.config.NotaryConfig
import net.corda.testing.ALICE
import net.corda.testing.BOB
import net.corda.testing.DUMMY_NOTARY_NAME
import net.corda.testing.internal.demorun.*
import java.nio.file.Paths

fun main(args: Array<String>) = CustomNotaryCordform().deployNodes()

class CustomNotaryCordform : CordformDefinition() {
    init {
        nodesDirectory = Paths.get("build", "nodes", "nodesCustom")
        node {
            name(ALICE.name)
            p2pPort(10002)
            rpcPort(10003)
            rpcUsers(notaryDemoUser)
        }
        node {
            name(BOB.name)
            p2pPort(10005)
            rpcPort(10006)
        }
        node {
            name(DUMMY_NOTARY_NAME)
            p2pPort(10009)
            rpcPort(10010)
            notary(NotaryConfig(validating = true, custom = true))
        }
    }

    override fun setup(context: CordformContext) {}
}