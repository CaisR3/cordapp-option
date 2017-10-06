package com.option

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.node.internal.StartedNode
import net.corda.option.ORACLE_NAME
import net.corda.option.contract.OptionContract
import net.corda.option.flow.OptionIssueFlow
import net.corda.option.flow.OptionRequestFlow
import net.corda.option.flow.OptionTradeFlow
import net.corda.option.flow.SelfIssueCashFlow
import net.corda.option.getBadOption
import net.corda.option.getOption
import net.corda.option.state.OptionState
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.node.MockNetwork
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionIssueFlowTests {
    val mockNet: MockNetwork = MockNetwork()
    lateinit var a: StartedNode<MockNetwork.MockNode>
    lateinit var b: StartedNode<MockNetwork.MockNode>

    @Before
    fun setup() {
        val nodes = mockNet.createSomeNodes(2)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]

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

    @Test
    fun issueFlowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val option = getOption(a,b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
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
        ptx.verifySignaturesExcept(b.info.legalIdentities.first().owningKey, DUMMY_NOTARY.owningKey)
    }

    @Test
    fun requestFlowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val cashFlow = SelfIssueCashFlow(100.DOLLARS)
        a.services.startFlow(cashFlow).resultFuture.getOrThrow()
        val option = getOption(a, b)
        val flow = OptionRequestFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        val ptx: SignedTransaction = future.getOrThrow()
        // Print the transaction for debugging purposes.
        println(ptx.tx)
        val commands = ptx.tx.commands
        assert(commands.last().value is OptionContract.Commands.Issue)
        assert(commands.last().signers.toSet() == option.participants.map { it.owningKey }.toSet())
        ptx.verifySignaturesExcept(b.info.legalIdentities.first().owningKey, DUMMY_NOTARY.owningKey)
    }

    @Test
    fun issueFlowFailsWhenZeroStrikeProvided() {
        // Check that a zero strike Option fails.
        val zeroStrikeOption = getBadOption(a,b)
        val futureOne = a.services.startFlow(OptionIssueFlow.Initiator(zeroStrikeOption)).resultFuture
        mockNet.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureOne.getOrThrow() }
    }

    @Test
    fun issueFlowReturnsTransactionSignedByBothParties() {
        val option = getOption(a, b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        val stx = future.getOrThrow()
        stx.verifyRequiredSignatures()
    }

    @Test
    fun issueFlowRecordsTheSameTransactionInBothPartyVaults() {
        val option = getOption(a,b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        val stx = future.getOrThrow()
        println("Signed transaction hash: ${stx.id}")
        for (node in listOf(a, b)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(stx.id)!!
            val txHash = recordedTx.id
            println("$txHash == ${stx.id}")
            assertEquals(stx.id, txHash)
        }
    }
}