package net.corda.option.flow

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.option.ORACLE_NAME
import net.corda.option.contract.OptionContract
import net.corda.option.createBadOption
import net.corda.option.createOption
import net.corda.option.flow.client.OptionIssueFlow
import net.corda.option.flow.client.OptionTradeFlow
import net.corda.option.state.OptionState
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.node.MockNetwork
import net.corda.testing.setCordappPackages
import net.corda.testing.unsetCordappPackages
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionIssueFlowTests {
    val mockNet: MockNetwork = MockNetwork()
    lateinit var issuerNode: StartedNode<MockNetwork.MockNode>
    lateinit var buyerNode: StartedNode<MockNetwork.MockNode>

    lateinit var issuer: Party
    lateinit var buyer: Party

    @Before
    fun setup() {
        setCordappPackages("net.corda.option.contract")
        val nodes = mockNet.createSomeNodes(2)
        issuerNode = nodes.partyNodes[0]
        buyerNode = nodes.partyNodes[1]

        issuer = issuerNode.info.legalIdentities.first()
        buyer = buyerNode.info.legalIdentities.first()

        val oracle = mockNet.createNode(nodes.mapNode.network.myAddress, legalName = ORACLE_NAME)
        oracle.internals.installCordaService(net.corda.option.service.Oracle::class.java)

        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
        }
        mockNet.runNetwork()
    }

    @After
    fun tearDown() {
        unsetCordappPackages()
        mockNet.stopNodes()
    }

    @Test
    fun issueFlowReturnsCorrectlyFormedSignedTransaction() {
        val stx = issueOptionToBuyer()

        // No inputs.
        assertEquals(0, stx.tx.inputs.size)

        // A single OptionState output.
        assertEquals(1, stx.tx.outputs.size)
        assert(stx.tx.outputStates.single() is OptionState)

        // A single command with the correct attributes.
        assertEquals(1, stx.tx.commands.size)
        val command = stx.tx.commands.single()
        assert(command.value is OptionContract.Commands.Issue)
        listOf(issuer, buyer).forEach {
            assert(command.signers.contains(it.owningKey))
        }
    }

    @Test
    fun issueFlowReturnsTransactionSignedByBothParties() {
        val stx = issueOptionToBuyer()
        stx.verifyRequiredSignatures()
    }

    @Test
    fun issueFlowFailsWhenZeroStrikeProvided() {
        // Check that a zero-strike-price option fails.
        val zeroStrikeOption = createBadOption(issuer, buyer)
        val futureOne = issuerNode.services.startFlow(OptionIssueFlow.Initiator(zeroStrikeOption)).resultFuture
        mockNet.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureOne.getOrThrow() }
    }

    @Test
    fun issueFlowRecordsTheSameTransactionInBothPartiesVaults() {
        val stx = issueOptionToBuyer()

        // TODO: Convert this to a test of the vault, not the transaction storage.
        for (node in listOf(issuerNode, buyerNode)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(stx.id)!!
            val txHash = recordedTx.id
            println("$txHash == ${stx.id}")
            assertEquals(stx.id, txHash)
        }
    }

    /** Issues an option from the issuer to buyer A. */
    private fun issueOptionToBuyer(): SignedTransaction {
        val option = createOption(issuer, buyer)
        val flow = OptionIssueFlow.Initiator(option)
        val future = buyerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }
}