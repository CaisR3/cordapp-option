package net.corda.option.flow

import net.corda.core.contracts.Amount
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.node.internal.StartedNode
import net.corda.option.DUMMY_LINEAR_ID
import net.corda.option.base.KNOWN_SPOTS
import net.corda.option.base.KNOWN_VOLATILITIES
import net.corda.option.base.OPTION_CURRENCY
import net.corda.option.base.ORACLE_NAME
import net.corda.option.base.contract.OptionContract
import net.corda.option.base.state.OptionState
import net.corda.option.client.flow.OptionIssueFlow
import net.corda.option.client.flow.OptionTradeFlow
import net.corda.option.createOption
import net.corda.option.oracle.flow.QueryOracleHandler
import net.corda.option.oracle.flow.RequestOracleSigHandler
import net.corda.option.oracle.oracle.Oracle
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import net.corda.testing.setCordappPackages
import net.corda.testing.unsetCordappPackages
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionTradeFlowTests {
    private lateinit var mockNet: MockNetwork
    private lateinit var issuerNode: StartedNode<MockNode>
    private lateinit var buyerANode: StartedNode<MockNode>
    private lateinit var buyerBNode: StartedNode<MockNode>
    private lateinit var oracleNode: StartedNode<MockNode>

    private lateinit var issuer: Party
    private lateinit var buyerA: Party
    private lateinit var buyerB: Party
    private lateinit var oracle: Party

    @Before
    fun setup() {
        setCordappPackages("net.corda.option.base.contract", "net.corda.finance.contracts.asset")

        mockNet = MockNetwork()

        val nodes = mockNet.createSomeNodes(3)
        issuerNode = nodes.partyNodes[0]
        buyerANode = nodes.partyNodes[1]
        buyerBNode = nodes.partyNodes[2]
        oracleNode = mockNet.createNode(nodes.mapNode.network.myAddress, legalName = ORACLE_NAME)

        oracleNode.internals.installCordaService(Oracle::class.java)
        oracleNode.registerInitiatedFlow(QueryOracleHandler::class.java)
        oracleNode.registerInitiatedFlow(RequestOracleSigHandler::class.java)

        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
        }

        issuer = issuerNode.info.legalIdentities.first()
        buyerA = buyerANode.info.legalIdentities.first()
        buyerB = buyerBNode.info.legalIdentities.first()
        oracle = oracleNode.info.legalIdentities.first()

        mockNet.runNetwork()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
        unsetCordappPackages()
    }

    @Test
    fun `trade flow records a correctly-formed transaction in both parties' transaction storages`() {
        issueCashToBuyerA()
        val option = createOption(issuer, buyerA)
        issueOptionToBuyerA(option)
        issueCashToBuyerB()
        val stx = tradeOptionWithBuyerB()

        // We check the recorded transaction in both vaults.
        listOf(issuerNode, buyerANode, buyerBNode).forEach { node ->
            assertEquals(stx, node.services.validatedTransactions.getTransaction(stx.id))

            val ltx = node.database.transaction {
                stx.toLedgerTransaction(node.services)
            }

            // A Cash.State input and an OptionState input.
            assertEquals(2, ltx.inputs.size)
            assertEquals(1, ltx.inputsOfType<Cash.State>().size)
            assertEquals(1, ltx.outputsOfType<OptionState>().size)

            // A Cash.State output and an OptionState output.
            assertEquals(2, ltx.outputs.size)
            assertEquals(1, ltx.outputsOfType<Cash.State>().size)
            assertEquals(1, ltx.outputsOfType<OptionState>().size)

            // Two commands.
            assertEquals(3, ltx.commands.size)

            // An OptionContract.Commands.Trade command with the correct attributes.
            val optionCmd = ltx.commandsOfType<OptionContract.Commands.Trade>().single()
            listOf(buyerA, buyerB).forEach {
                assert(optionCmd.signers.containsAll(listOf(buyerA.owningKey, buyerB.owningKey)))
            }

            // An OptionContract.OracleCommand with the correct attributes.
            val oracleCmd = ltx.commandsOfType<OptionContract.OracleCommand>().single()
            assert(oracleCmd.signers.contains(oracle.owningKey))
            assertEquals(KNOWN_SPOTS[0], oracleCmd.value.spotPrice)
            assertEquals(KNOWN_VOLATILITIES[0], oracleCmd.value.volatility)

            // A Cash.Commands.Move command with the correct attributes.
            val cashCmd = ltx.commandsOfType<Cash.Commands.Move>().single()
            assert(cashCmd.signers.contains(buyerB.owningKey))
        }
    }

    @Test
    fun `flow records the option in the vaults of the issuer and current owner only`() {
        issueCashToBuyerA()
        val option = createOption(issuer, buyerA)
        issueOptionToBuyerA(option)
        issueCashToBuyerB()
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

    private fun issueCashToBuyerA() {
        val notary = buyerANode.services.networkMapCache.notaryIdentities.first()
        val flow = CashIssueFlow(Amount(900, OPTION_CURRENCY), OpaqueBytes.of(0x01), notary)
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()
    }

    private fun issueCashToBuyerB() {
        val notary = buyerBNode.services.networkMapCache.notaryIdentities.first()
        val flow = CashIssueFlow(Amount(900, OPTION_CURRENCY), OpaqueBytes.of(0x01), notary)
        val future = buyerBNode.services.startFlow(flow).resultFuture
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