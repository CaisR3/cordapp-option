package net.corda.option.flow

import net.corda.core.contracts.requireSingleCommand
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.node.internal.StartedNode
import net.corda.option.ORACLE_NAME
import net.corda.option.SpotPrice
import net.corda.option.Stock
import net.corda.option.contract.OptionContract
import net.corda.option.createOption
import net.corda.option.flow.client.OptionExerciseFlow
import net.corda.option.flow.client.OptionIssueFlow
import net.corda.option.flow.client.OptionTradeFlow
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionExerciseFlowTests {
    val mockNet: MockNetwork = MockNetwork()
    lateinit var a: StartedNode<MockNode>
    lateinit var b: StartedNode<MockNode>

    lateinit var partyA: Party
    lateinit var partyB: Party

    @Before
    fun setup() {
        val nodes = mockNet.createSomeNodes(2)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]

        partyA = a.info.legalIdentities.first()
        partyB = b.info.legalIdentities.first()

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
        mockNet.stopNodes()
    }

    private fun issueOption(): SignedTransaction {
        val option = createOption(partyA , partyB)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }

    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val stx = issueOption()
        val time = Instant.parse("2017-07-03T10:15:30.00Z")
        val option = createOption(partyA , partyB)
        val spotValue = 3.DOLLARS
        val spot = SpotPrice(Stock(option.underlyingStock, time), spotValue)

        val inputOption = stx.tx.outputs.single().data as OptionState
        val flow = OptionExerciseFlow.Initiator(option.linearId)
        val future = b.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        val exerciseResult = future.getOrThrow()
        // Check the transaction is well formed...
        val ledgerTx = exerciseResult.toLedgerTransaction(a.services)
        assert(ledgerTx.inputs.size == 1)
        assert(ledgerTx.outputs.size == 2)

        //Check IOU issues post exercising
        val iou = ledgerTx.outputs.filter { it.data is IOUState }.single().data as IOUState
        val amount = iou.amount
        // Compare the cash assigned to the lender with the amount claimed is being settled by the borrower.
        assertEquals(
                amount,
                (inputOption.strike - 3.DOLLARS))
        val command = ledgerTx.commands.requireSingleCommand<OptionContract.Commands>().value as OptionContract.Commands.Exercise
        assert(command.spot == OptionContract.Commands.Exercise(spot).spot)
        // Check the transaction has been signed by the borrower.
        exerciseResult.verifySignaturesExcept(partyB.owningKey, DUMMY_NOTARY.owningKey)
    }

    @Test
    fun exerciseFlowCanOnlyBeRunByOwner() {
        val flow = OptionExerciseFlow.Initiator(createOption(partyA , partyB).linearId)
        val future = a.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }
}
