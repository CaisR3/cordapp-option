package net.corda.option

import com.google.common.util.concurrent.Futures
import net.corda.core.getOrThrow
import net.corda.core.node.services.ServiceInfo
import net.corda.node.driver.driver
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.User
import net.corda.option.oracle.service.Oracle
import org.bouncycastle.asn1.x500.X500Name

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

        val (controller, nodeA, nodeB, nodeC, nodeD) = Futures.allAsList(
                startNode(X500Name("CN=Controller,O=R3,OU=corda,L=London,C=UK"), setOf(ServiceInfo(SimpleNotaryService.type))),
                startNode(X500Name("CN=NodeA,O=NodeA,L=London,C=UK"), rpcUsers = listOf(user)),
                startNode(X500Name("CN=NodeB,O=NodeB,L=New York,C=US"), rpcUsers = listOf(user)),
                startNode(X500Name("CN=NodeC,O=NodeC,L=Paris,C=FR"), rpcUsers = listOf(user)),
                startNode(X500Name("CN=NodeD,O=NodeD,L=Paris,C=FR"), setOf(ServiceInfo(Oracle.type)), rpcUsers = listOf(user))
        ).getOrThrow()

        startWebserver(controller)
        startWebserver(nodeA)
        startWebserver(nodeB)
        startWebserver(nodeC)
        startWebserver(nodeD)

        waitForAllNodesToFinish()
    }, useTestClock = true, isDebug = true)
}