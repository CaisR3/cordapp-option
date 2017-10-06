package net.corda.option

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.User
import net.corda.nodeapi.internal.ServiceInfo
import net.corda.option.oracle.service.Oracle
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
    driver(dsl = {
        val user = User("user1", "test", permissions = setOf())

        val (controller, nodeA, nodeB, nodeC, nodeD) = listOf(
                startNode(providedName = CordaX500Name("Controller", "London", "GB"), advertisedServices = setOf(ServiceInfo(SimpleNotaryService.type))),
                startNode(providedName = CordaX500Name("NodeA", "London", "GB"), rpcUsers = listOf(user)),
                startNode(providedName = CordaX500Name("NodeB", "New York", "US"), rpcUsers = listOf(user)),
                startNode(providedName = CordaX500Name("NodeC", "Paris" , "FR"), rpcUsers = listOf(user)),
                startNode(providedName = CordaX500Name("NodeD", "New York", "US"), rpcUsers = listOf(user))
        ).map { it.getOrThrow() }

        startWebserver(controller)
        startWebserver(nodeA)
        startWebserver(nodeB)
        startWebserver(nodeC)
        startWebserver(nodeD)

        waitForAllNodesToFinish()
    }, useTestClock = true, isDebug = true)
}