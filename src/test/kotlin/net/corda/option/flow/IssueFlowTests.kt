package com.option

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.getOrThrow
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.option.contract.OptionContract
import net.corda.testing.node.MockNetwork
import net.corda.option.getBadOption
import net.corda.option.getOption
import net.corda.option.flow.*
import net.corda.option.state.OptionState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionIssueFlowTests {
    lateinit var net: MockNetwork
    lateinit var a: MockNetwork.MockNode
    lateinit var b: MockNetwork.MockNode
    lateinit var c: MockNetwork.MockNode

    @Before
    fun setup() {
        net = MockNetwork()
        val nodes = net.createSomeNodes(3)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]
        c = nodes.partyNodes[2]
        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
        }
        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
        }
        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionExerciseFlow.Responder::class.java)
        }
        net.runNetwork()
    }

    @After
    fun tearDown() {
        net.stopNodes()
    }

    @Test
    fun issueFlowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val option = getOption(a,b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        val ptx: SignedTransaction = future.getOrThrow()
        // Print the transaction for debugging purposes.
        println(ptx.tx)
        // Check the transaction is well formed...
        // No outputs, one input OptionState and a command with the right properties.
        assert(ptx.tx.inputs.isEmpty())
        assert(ptx.tx.outputs.single().data is OptionState)
        val command = ptx.tx.commands.single()
        assert(command.value is OptionContract.Commands.Issue)
        assert(command.signers.toSet() == option.participants.map { it.owningKey }.toSet())
        ptx.verifySignatures(b.info.legalIdentity.owningKey, DUMMY_NOTARY.owningKey)
    }

    @Test
    fun issueFlowReturnsVerifiedPartiallySignedTransaction() {
        // Check that a zero strike Option fails.
        val zeroStrikeOption = getBadOption(a,b)
        val futureOne = a.services.startFlow(OptionIssueFlow.Initiator(zeroStrikeOption)).resultFuture
        net.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureOne.getOrThrow() }
    }

    @Test
    fun issueFlowReturnsTransactionSignedByBothParties() {
        val option = getOption(a,b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        val stx = future.getOrThrow()
        stx.verifySignatures()
    }

    @Test
    fun issueFlowRecordsTheSameTransactionInBothPartyVaults() {
        val option = getOption(a,b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        val stx = future.getOrThrow()
        println("Signed transaction hash: ${stx.id}")
        listOf(a, b).map {
            it.storage.validatedTransactions.getTransaction(stx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${stx.id}")
            assertEquals(stx.id, txHash)
        }
    }
}