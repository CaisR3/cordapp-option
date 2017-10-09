package net.corda.option.flow

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.option.OPTION_LINEAR_ID
import net.corda.option.ORACLE_NAME
import net.corda.option.contract.OptionContract
import net.corda.option.createOption
import net.corda.option.flow.client.OptionIssueFlow
import net.corda.option.flow.client.OptionTradeFlow
import net.corda.option.state.OptionState
import net.corda.testing.node.MockNetwork
import net.corda.testing.setCordappPackages
import net.corda.testing.unsetCordappPackages
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionTradeFlowTests {
    val mockNet: MockNetwork = MockNetwork()
    lateinit var issuerNode: StartedNode<MockNetwork.MockNode>
    lateinit var buyerANode: StartedNode<MockNetwork.MockNode>
    lateinit var buyerBNode: StartedNode<MockNetwork.MockNode>

    lateinit var issuer: Party
    lateinit var buyerA: Party
    lateinit var buyerB: Party

    @Before
    fun setup() {
        setCordappPackages("net.corda.option.contract")

        val nodes = mockNet.createSomeNodes(3)
        issuerNode = nodes.partyNodes[0]
        buyerANode = nodes.partyNodes[1]
        buyerBNode = nodes.partyNodes[2]

        issuer = issuerNode.info.legalIdentities.first()
        buyerA = buyerANode.info.legalIdentities.first()
        buyerB = buyerBNode.info.legalIdentities.first()

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
    fun tradeFlowReturnsCorrectlyFormedSignedTransaction() {
        issueOptionToBuyerA()
        val stx = tradeOptionWithBuyerB()

        // A single OptionState input.
        assertEquals(1, stx.tx.inputs.size)
        // TODO: Work out how to convert to a ledger transaction without an error.

        // A single OptionState output.
        assertEquals(1, stx.tx.outputs.size)
        assert(stx.tx.outputStates.single() is OptionState)

        // A single command with the correct attributes.
        assertEquals(1, stx.tx.commands.size)
        val command = stx.tx.commands.single()
        assert(command.value is OptionContract.Commands.Trade)
        listOf(issuer, buyerA, buyerB).forEach {
            assert(command.signers.contains(it.owningKey))
        }
    }

    @Test
    fun tradeFlowReturnsTransactionSignedByAllPartiesAndNotary() {
        issueOptionToBuyerA()
        val stx = tradeOptionWithBuyerB()
        stx.verifyRequiredSignatures()
    }

    @Test
    fun tradeFlowRecordsTransactionInIssuerAndOwnersVaults() {
        // TODO: Modify to actually check vaults.
        issueOptionToBuyerA()
        val stx = tradeOptionWithBuyerB()
        stx.verifyRequiredSignatures()
    }

    @Test
    fun flowCanOnlyBeRunByCurrentOwner() {
        issueOptionToBuyerA()
        val flow = OptionTradeFlow.Initiator(OPTION_LINEAR_ID, buyerB)
        // We are running the flow from the issuer, who doesn't currently own the option.
        val future = issuerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }

    @Test
    fun optionCannotBeTransferredToSameParty() {
        issueOptionToBuyerA()
        val flow = OptionTradeFlow.Initiator(OPTION_LINEAR_ID, buyerA)
        // Buyer A already owns the option that they're trying to transfer to themselves.
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    /** Issues an option from the issuer to buyer A. */
    private fun issueOptionToBuyerA() {
        val option = createOption(issuer, buyerA)
        val flow = OptionIssueFlow.Initiator(option)
        buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
    }

    /** Transfers the option from buyer A to buyer B. */
    private fun tradeOptionWithBuyerB(): SignedTransaction {
        val flow = OptionTradeFlow.Initiator(OPTION_LINEAR_ID, buyerB)
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }
}