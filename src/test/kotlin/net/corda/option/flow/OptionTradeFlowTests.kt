package net.corda.option.flow

import net.corda.core.contracts.Amount
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.node.internal.StartedNode
import net.corda.option.DUMMY_LINEAR_ID
import net.corda.option.OPTION_CURRENCY
import net.corda.option.ORACLE_NAME
import net.corda.option.contract.OptionContract
import net.corda.option.createOption
import net.corda.option.flow.client.OptionIssueFlow
import net.corda.option.flow.client.OptionTradeFlow
import net.corda.option.flow.oracle.QueryOracleHandler
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
    private lateinit var mockNet: MockNetwork
    private lateinit var issuerNode: StartedNode<MockNetwork.MockNode>
    private lateinit var buyerANode: StartedNode<MockNetwork.MockNode>
    private lateinit var buyerBNode: StartedNode<MockNetwork.MockNode>

    private lateinit var issuer: Party
    private lateinit var buyerA: Party
    private lateinit var buyerB: Party

    @Before
    fun setup() {
        setCordappPackages("net.corda.option.contract", "net.corda.finance.contracts.asset")

        mockNet = MockNetwork()

        val nodes = mockNet.createSomeNodes(3)
        issuerNode = nodes.partyNodes[0]
        buyerANode = nodes.partyNodes[1]
        buyerBNode = nodes.partyNodes[2]

        issuer = issuerNode.info.legalIdentities.first()
        buyerA = buyerANode.info.legalIdentities.first()
        buyerB = buyerBNode.info.legalIdentities.first()

        val oracle = mockNet.createNode(nodes.mapNode.network.myAddress, legalName = ORACLE_NAME)
        oracle.internals.installCordaService(net.corda.option.service.Oracle::class.java)
        oracle.registerInitiatedFlow(QueryOracleHandler::class.java)

        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
        }
        mockNet.runNetwork()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
        unsetCordappPackages()
    }

//    @Test
//    fun `trade flow records a correctly-formed transaction in both parties' transaction storages`() {
//        issueCashToBuyerA()
//        val option = createOption(issuer, buyerA)
//        issueOptionToBuyerA(option)
//        tradeOptionWithBuyerB()
//
//        // We check the recorded transaction in both vaults.
//        listOf(issuerNode, buyerNode).forEach { node ->
//            assertEquals(stx, node.services.validatedTransactions.getTransaction(stx.id))
//
//            val ltx = node.database.transaction {
//                stx.toLedgerTransaction(node.services)
//            }
//
//            // A single Cash.State input.
//            assertEquals(1, ltx.inputs.size)
//            assertEquals(1, ltx.inputsOfType<Cash.State>().size)
//
//            // A Cash.State output and an OptionState output.
//            assertEquals(2, ltx.outputs.size)
//            assertEquals(1, ltx.outputsOfType<Cash.State>().size)
//            assertEquals(1, ltx.outputsOfType<OptionState>().size)
//
//            // Two commands.
//            assertEquals(2, ltx.commands.size)
//
//            // An OptionContract.Commands.Issue command with the correct attributes.
//            val optionCmd = ltx.commandsOfType<OptionContract.Commands.Issue>().single()
//            listOf(issuer, buyer).forEach {
//                assert(optionCmd.signers.contains(it.owningKey))
//            }
//
//            // A Cash.Commands.Move command with the correct attributes.
//            val cashCmd = ltx.commandsOfType<Cash.Commands.Move>().single()
//            assert(cashCmd.signers.contains(buyer.owningKey))
//        }
//    }

    @Test
    fun `flow records the correct option in both parties' vaults`() {
        issueCashToBuyerA()
        val option = createOption(issuer, buyerA)
        issueOptionToBuyerA(option)
        tradeOptionWithBuyerB()

        // The option is recorded in the vaults of the issuer and current owner.
        listOf(issuerNode, buyerBNode).forEach { node ->
            val options = node.database.transaction {
                node.services.vaultService.queryBy<OptionState>().states
            }
            assertEquals(1, options.size)
            val recordedOption = options.single().state.data
            // The only difference is the owner.
            assertEquals(option, recordedOption.copy(owner = option.owner))
        }

        // The option is not recorded in the vault of the old owner.
        val options = buyerANode.database.transaction {
            buyerANode.services.vaultService.queryBy<OptionState>().states
        }
        assertEquals(0, options.size)
    }

    @Test
    fun `trade flow can only be run by the current owner`() {
        issueCashToBuyerA()
        val option = createOption(issuer, buyerA)
        issueOptionToBuyerA(option)
        val flow = OptionTradeFlow.Initiator(DUMMY_LINEAR_ID, buyerB)
        // We are running the flow from the issuer, who doesn't currently own the option.
        val future = issuerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }

    @Test
    fun optionCannotBeTransferredToSameParty() {
        issueCashToBuyerA()
        val option = createOption(issuer, buyerA)
        issueOptionToBuyerA(option)
        val flow = OptionTradeFlow.Initiator(DUMMY_LINEAR_ID, buyerA)
        // Buyer A already owns the option that they're trying to transfer to themselves.
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    private fun issueCashToBuyerA() {
        val notary = buyerANode.services.networkMapCache.notaryIdentities.first()
        val flow = CashIssueFlow(Amount(900, OPTION_CURRENCY), OpaqueBytes.of(0x01), notary)
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()
    }

    private fun issueOptionToBuyerA(option: OptionState): SignedTransaction {
        val flow = OptionIssueFlow.Initiator(option)
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }

    private fun tradeOptionWithBuyerB(): SignedTransaction {
        val flow = OptionTradeFlow.Initiator(DUMMY_LINEAR_ID, buyerB)
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }
}