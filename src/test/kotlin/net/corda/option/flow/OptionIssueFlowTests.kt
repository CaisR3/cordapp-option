package net.corda.option.flow

import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.schemas.CashSchemaV1
import net.corda.node.internal.StartedNode
import net.corda.option.*
import net.corda.option.contract.OptionContract
import net.corda.option.flow.client.OptionIssueFlow
import net.corda.option.flow.oracle.QueryOracleHandler
import net.corda.option.flow.oracle.RequestOracleSigHandler
import net.corda.option.state.OptionState
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import net.corda.testing.setCordappPackages
import net.corda.testing.unsetCordappPackages
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionIssueFlowTests {
    private lateinit var mockNet: MockNetwork
    private lateinit var issuerNode: StartedNode<MockNode>
    private lateinit var buyerNode: StartedNode<MockNode>
    private lateinit var oracleNode: StartedNode<MockNode>

    private lateinit var issuer: Party
    private lateinit var buyer: Party
    private lateinit var oracle: Party

    @Before
    fun setup() {
        setCordappPackages("net.corda.option.contract", "net.corda.finance.contracts.asset")

        mockNet = MockNetwork()
        val nodes = mockNet.createSomeNodes(2)
        issuerNode = nodes.partyNodes[0]
        buyerNode = nodes.partyNodes[1]
        oracleNode = mockNet.createNode(nodes.mapNode.network.myAddress, legalName = ORACLE_NAME)

        oracleNode.internals.installCordaService(net.corda.option.service.Oracle::class.java)
        oracleNode.registerInitiatedFlow(QueryOracleHandler::class.java)
        oracleNode.registerInitiatedFlow(RequestOracleSigHandler::class.java)

        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
            it.internals.registerCustomSchemas(setOf(CashSchemaV1))
        }

        issuer = issuerNode.info.legalIdentities.first()
        buyer = buyerNode.info.legalIdentities.first()
        oracle = oracleNode.info.legalIdentities.first()

        mockNet.runNetwork()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
        unsetCordappPackages()
    }

    @Test
    fun `issue flow records a correctly-formed transaction in both parties' transaction storages`() {
        issueCashToBuyer()
        val option = createOption(issuer, buyer)
        val stx = issueOptionToBuyer(option)

        // We check the recorded transaction in both vaults.
        listOf(issuerNode, buyerNode).forEach { node ->
            assertEquals(stx, node.services.validatedTransactions.getTransaction(stx.id))

            val ltx = node.database.transaction {
                stx.toLedgerTransaction(node.services)
            }

            // A single Cash.State input.
            assertEquals(1, ltx.inputs.size)
            assertEquals(1, ltx.inputsOfType<Cash.State>().size)

            // A Cash.State output and an OptionState output.
            assertEquals(2, ltx.outputs.size)
            assertEquals(1, ltx.outputsOfType<Cash.State>().size)
            assertEquals(1, ltx.outputsOfType<OptionState>().size)

            // Two commands.
            assertEquals(3, ltx.commands.size)

            // An OptionContract.Commands.Issue command with the correct attributes.
            val optionCmd = ltx.commandsOfType<OptionContract.Commands.Issue>().single()
            assert(optionCmd.signers.containsAll(listOf(issuer.owningKey, buyer.owningKey)))

            // An OptionContract.OracleCommand with the correct attributes.
            val oracleCmd = ltx.commandsOfType<OptionContract.OracleCommand>().single()
            assert(oracleCmd.signers.contains(oracle.owningKey))
            assertEquals(KNOWN_SPOTS[0], oracleCmd.value.spotPrice)
            assertEquals(KNOWN_VOLATILITIES[0], oracleCmd.value.volatility)

            // A Cash.Commands.Move command with the correct attributes.
            val cashCmd = ltx.commandsOfType<Cash.Commands.Move>().single()
            assert(cashCmd.signers.contains(buyer.owningKey))
        }
    }

    @Test
    fun `flow records the option in the vaults of the issuer and owner`() {
        issueCashToBuyer()
        val option = createOption(issuer, buyer)
        issueOptionToBuyer(option)

        // We check the recorded IOU in both vaults.
        listOf(issuerNode, buyerNode).forEach { node ->
            val options = node.database.transaction {
                node.services.vaultService.queryBy<OptionState>().states
            }
            assertEquals(1, options.size)
            val recordedOption = options.single().state.data
            assertEquals(option, recordedOption)
        }
    }

    @Test
    fun `flow records the correct cash in the issuer's vault`() {
        issueCashToBuyer()
        val option = createOption(issuer, buyer)
        issueOptionToBuyer(option)

        // We check the recorded IOU in both vaults.
        val cash = issuerNode.database.transaction {
            issuerNode.services.vaultService.queryBy<Cash.State>().states
        }
        assertEquals(1, cash.size)
        val recordedCash = cash.single().state.data
        assertEquals(recordedCash.amount.quantity, 900)
        assertEquals(recordedCash.amount.token.product, OPTION_CURRENCY)
    }

    @Test
    fun `issue flow can only be run by the buyer`() {
        issueCashToBuyer()
        val option = createOption(issuer, buyer)
        val flow = OptionIssueFlow.Initiator(option)
        val future = issuerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }

    @Test
    fun `issue flow rejects options with an expiry date in the past`() {
        issueCashToBuyer()
        val badOption = createBadOption(issuer, buyer)
        val futureOne = buyerNode.services.startFlow(OptionIssueFlow.Initiator(badOption)).resultFuture
        mockNet.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureOne.getOrThrow() }
    }

    @Test
    fun `issue flow fails if the buyer does not have enough cash`() {
        val option = createOption(issuer, buyer)
        val flow = OptionIssueFlow.Initiator(option)
        val future = buyerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<InsufficientBalanceException> { future.getOrThrow() }
    }

    private fun issueCashToBuyer() {
        val notary = buyerNode.services.networkMapCache.notaryIdentities.first()
        val flow = CashIssueFlow(Amount(900, OPTION_CURRENCY), OpaqueBytes.of(0x01), notary)
        val future = buyerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()
    }

    private fun issueOptionToBuyer(option: OptionState): SignedTransaction {
        val flow = OptionIssueFlow.Initiator(option)
        val future = buyerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }
}