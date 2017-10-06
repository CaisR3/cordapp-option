package com.option

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.option.ORACLE_NAME
import net.corda.option.contract.OptionContract
import net.corda.option.flow.OptionIssueFlow
import net.corda.option.flow.OptionTradeFlow
import net.corda.option.getOption
import net.corda.option.state.OptionState
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.node.MockNetwork
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class OptionTradeFlowTests {
    val mockNet: MockNetwork = MockNetwork()
    lateinit var a: StartedNode<MockNetwork.MockNode>
    lateinit var b: StartedNode<MockNetwork.MockNode>
    lateinit var c: StartedNode<MockNetwork.MockNode>

    @Before
    fun setup() {
        val nodes = mockNet.createSomeNodes(3)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]
        c = nodes.partyNodes[2]

        val oracle = mockNet.createNode(nodes.mapNode.network.myAddress, legalName = ORACLE_NAME)
        oracle.internals.installCordaService(net.corda.option.oracle.service.Oracle::class.java)

        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
        }
        mockNet.runNetwork()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
    }

    private fun issueOption(): SignedTransaction {
        val option = getOption(a,b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }

    @Test
    fun tradeFlowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val stx = issueOption()
        println("Signed transaction hash: ${stx.id}")

        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentities.first())
        val future = b.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        val ptx: SignedTransaction = future.getOrThrow()
        // Print the transaction for debugging purposes.
        println(ptx.tx)
        // Check the transaction is well formed...
        // No outputs, one input OptionState and a command with the right properties.
        assert(ptx.tx.inputs.size == 1)
        assert(ptx.tx.outputs.single().data is OptionState)
        val command = ptx.tx.commands.single()
        assert(command.value is OptionContract.Commands.Trade)
        ptx.verifySignaturesExcept(c.info.legalIdentities.first().owningKey, DUMMY_NOTARY.owningKey)
    }

    @Test
    fun flowCanOnlyBeRunByCurrentOwner() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentities.first())
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }

    @Test
    fun optionCannotBeTransferredToSameParty() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, b.info.legalIdentities.first())
        val future = b.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        // Check that we can't transfer an Option to ourselves.
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    @Test
    fun tradeFlowReturnsTransactionSignedByAllParties() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentities.first())
        val future = b.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        future.getOrThrow().verifySignaturesExcept(DUMMY_NOTARY.owningKey)
    }

    @Test
    fun tradeFlowReturnsTransactionSignedByAllPartiesAndNotary() {
        issueOption()
        val flow = OptionTradeFlow.Initiator(getOption(a,b).linearId, c.info.legalIdentities.first())
        val future = b.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        future.getOrThrow().verifyRequiredSignatures()
    }
}