package net.corda.traderdemo

import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.div
import net.corda.finance.flows.CashIssueFlow
import net.corda.node.services.Permissions.Companion.all
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.nodeapi.internal.config.User
import net.corda.testing.driver.driver
import net.corda.traderdemo.flow.CommercialPaperIssueFlow
import net.corda.traderdemo.flow.SellerFlow

/**
 * This file is exclusively for being able to run your nodes through an IDE (as opposed to running deployNodes)
 * Do not use in a production environment.
 */
fun main(args: Array<String>) {
    val permissions = setOf(
            startFlow<CashIssueFlow>(),
            startFlow<SellerFlow>(),
            all())
    val demoUser = listOf(User("demo", "demo", permissions))
    driver(driverDirectory = "build" / "trader-demo-nodes", isDebug = true, waitForAllNodesToFinish = true) {
        val user = User("user1", "test", permissions = setOf(startFlow<CashIssueFlow>(),
                startFlow<CommercialPaperIssueFlow>(),
                startFlow<SellerFlow>()))
        startNode(providedName = CordaX500Name("Bank A", "London", "GB"), rpcUsers = demoUser)
        startNode(providedName = CordaX500Name("Bank B", "New York", "US"), rpcUsers = demoUser)
        startNode(providedName = CordaX500Name("BankOfCorda", "London", "GB"), rpcUsers = listOf(user))
    }
}
