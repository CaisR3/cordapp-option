package com.option

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.testing.node.MockNetwork
import net.corda.option.flow.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith
import net.corda.core.contracts.StateRef
import net.corda.core.getOrThrow
import net.corda.option.contract.OptionContract
import net.corda.option.getOption
import net.corda.option.state.OptionState

class OptionTradeFlowTests {
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
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
        }

        net.runNetwork()
    }

    @After
    fun tearDown() {
        net.stopNodes()
    }

    private fun issueOption(): SignedTransaction {
        val option = getOption(a,b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        return future.getOrThrow()
    }

    @Test
    fun tradeFlowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val stx = issueOption()
        println("Signed transaction hash: ${stx.id}")

        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentity)
        val future = b.services.startFlow(flow).resultFuture
        net.runNetwork()
        val ptx: SignedTransaction = future.getOrThrow()
        // Print the transaction for debugging purposes.
        println(ptx.tx)
        // Check the transaction is well formed...
        // No outputs, one input OptionState and a command with the right properties.
        assert(ptx.tx.inputs.size == 1)
        assert(ptx.tx.outputs.single().data is OptionState)
        val command = ptx.tx.commands.single()
        assert(command.value is OptionContract.Commands.Trade)
        ptx.verifySignatures(c.info.legalIdentity.owningKey, DUMMY_NOTARY.owningKey)
    }

    @Test
    fun flowCanOnlyBeRunByCurrentOwner() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentity)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }

    @Test
    fun optionCannotBeTransferredToSameParty() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, b.info.legalIdentity)
        val future = b.services.startFlow(flow).resultFuture
        net.runNetwork()
        // Check that we can't transfer an Option to ourselves.
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    @Test
    fun tradeFlowReturnsTransactionSignedByAllParties() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentity)
        val future = b.services.startFlow(flow).resultFuture
        net.runNetwork()
        future.getOrThrow().verifySignatures(DUMMY_NOTARY.owningKey)
    }

    @Test
    fun tradeFlowReturnsTransactionSignedByAllPartiesAndNotary() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentity)
        val future = b.services.startFlow(flow).resultFuture
        net.runNetwork()
        future.getOrThrow().verifySignatures()
    }
}