package net.corda.option.flow

import net.corda.core.contracts.Amount
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.flows.CashIssueFlow
import net.corda.node.internal.StartedNode
import net.corda.option.DUMMY_LINEAR_ID
import net.corda.option.OPTION_CURRENCY
import net.corda.option.ORACLE_NAME
import net.corda.option.contract.OptionContract
import net.corda.option.createOption
import net.corda.option.flow.client.OptionExerciseFlow
import net.corda.option.flow.client.OptionIssueFlow
import net.corda.option.flow.client.OptionTradeFlow
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

class OptionExerciseFlowTests {
    private val mockNet: MockNetwork = MockNetwork()
    private lateinit var issuerNode: StartedNode<MockNode>
    private lateinit var buyerNode: StartedNode<MockNode>
    private lateinit var oracleNode: StartedNode<MockNode>

    private lateinit var issuer: Party
    private lateinit var buyer: Party
    private lateinit var oracle: Party

    @Before
    fun setup() {
        setCordappPackages("net.corda.option.contract", "net.corda.finance.contracts.asset")

        val nodes = mockNet.createSomeNodes(2)
        issuerNode = nodes.partyNodes[0]
        buyerNode = nodes.partyNodes[1]
        oracleNode = mockNet.createNode(nodes.mapNode.network.myAddress, legalName = ORACLE_NAME)

        oracleNode.internals.installCordaService(net.corda.option.service.Oracle::class.java)
        oracleNode.registerInitiatedFlow(QueryOracleHandler::class.java)
        oracleNode.registerInitiatedFlow(RequestOracleSigHandler::class.java)

        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
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
        issueOptionToBuyer(option)
        val stx = exerciseOption()

        // We check the recorded transaction in both vaults.
        listOf(issuerNode, buyerNode).forEach { node ->
            assertEquals(stx, node.services.validatedTransactions.getTransaction(stx.id))

            val ltx = node.database.transaction {
                stx.toLedgerTransaction(node.services)
            }

            // An OptionState input.
            assertEquals(1, ltx.inputs.size)
            assertEquals(1, ltx.inputsOfType<OptionState>().size)

            // An OptionState output.
            assertEquals(1, ltx.outputs.size)
            assertEquals(1, ltx.outputsOfType<OptionState>().size)

            // A single OptionContract.Commands.Exercise command with the correct attributes.
            assertEquals(1, ltx.commands.size)
            val optionCmd = ltx.commandsOfType<OptionContract.Commands.Exercise>().single()
            assert(optionCmd.signers.containsAll(listOf(buyer.owningKey)))
        }
    }

    @Test
    fun `flow records the option in the vaults of the issuer and owner`() {
        issueCashToBuyer()
        val option = createOption(issuer, buyer)
        issueOptionToBuyer(option)
        exerciseOption()

        // We check the recorded option in both vaults.
        listOf(issuerNode, buyerNode).forEach { node ->
            val options = node.database.transaction {
                node.services.vaultService.queryBy<OptionState>().states
            }
            assertEquals(1, options.size)
            val recordedOption = options.single().state.data
            assertEquals(option, recordedOption.copy(exercised = false, exercisedOnDate = option.exercisedOnDate))
        }
    }

    @Test
    fun `exercise flow can only be run by the current owner`() {
        issueCashToBuyer()
        val option = createOption(issuer, buyer)
        issueOptionToBuyer(option)
        val flow = OptionExerciseFlow.Initiator(DUMMY_LINEAR_ID)
        // We are running the flow from the issuer, who doesn't currently own the option.
        val future = issuerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
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

    private fun exerciseOption(): SignedTransaction {
        val flow = OptionExerciseFlow.Initiator(DUMMY_LINEAR_ID)
        val future = buyerNode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }
}
