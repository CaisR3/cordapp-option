package net.corda.option

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.User
import net.corda.nodeapi.internal.ServiceInfo
import net.corda.testing.driver.driver

/**
 * This file is exclusively for being able to run your nodes through an IDE (as opposed to running deployNodes via
 * Gradle).
 *
 * Do not use in a production environment.
 *
 * To debug your CorDapp:
 *
 * 1. Run the "Run CorDapp - Kotlin" run configuration.
 * 2. Wait for all the nodes to start.
 * 3. Note the debug ports for each node, which should be output to the console. The "Debug CorDapp" configuration runs
 *    with port 5007, which should be "NodeA". In any case, double-check the console output to be sure.
 * 4. Set your breakpoints in your CorDapp code.
 * 5. Run the "Debug CorDapp" remote debug run configuration.
 */
fun main(args: Array<String>) {
    driver(
            startNodesInProcess = true,
            extraCordappPackagesToScan = listOf("net.corda.option.base", "net.corda.option.client", "net.corda.option.service", "net.corda.finance.contracts.asset"),
            dsl = {
                val user = User("user1", "test", permissions = setOf())

                // TODO: Re-add issuer, partyA
                val (controller, partyB, oracle) = listOf(
                        startNode(providedName = CordaX500Name("Controller", "London", "GB"), advertisedServices = setOf(ServiceInfo(SimpleNotaryService.type))),
//                        startNode(providedName = CordaX500Name("Issuer", "London", "GB"), rpcUsers = listOf(user)),
//                        startNode(providedName = CordaX500Name("PartyA", "New York", "US"), rpcUsers = listOf(user)),
                        startNode(providedName = CordaX500Name("PartyB", "Paris", "FR"), rpcUsers = listOf(user)),
                        startNode(providedName = CordaX500Name("Oracle", "New York", "US"), rpcUsers = listOf(user))
                ).map { it.getOrThrow() }

                startWebserver(controller)
//                startWebserver(issuer)
//                startWebserver(partyA)
                startWebserver(partyB)
                startWebserver(oracle)

                waitForAllNodesToFinish()
            }, useTestClock = true, isDebug = true)
}