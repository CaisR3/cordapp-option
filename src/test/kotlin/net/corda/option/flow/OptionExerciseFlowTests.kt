package net.corda.option.flow

import net.corda.core.contracts.requireSingleCommand
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.node.internal.StartedNode
import net.corda.option.DUMMY_LINEAR_ID
import net.corda.option.KNOWN_SPOTS
import net.corda.option.ORACLE_NAME
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OptionExerciseFlowTests {
    private val mockNet: MockNetwork = MockNetwork()
    private lateinit var issuerNode: StartedNode<MockNode>
    private lateinit var buyerNode: StartedNode<MockNode>

    private lateinit var issuer: Party
    private lateinit var buyer: Party

    @Before
    fun setup() {
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
        mockNet.stopNodes()
    }

    @Test
    fun flowReturnsCorrectlyFormedSignedTransaction() {
        val issueTx = issueOptionToBuyer()
        val exerciseTx = exerciseOption()

        val inputOption = issueTx.tx.outputsOfType<OptionState>().single()
        val spotPrice = KNOWN_SPOTS[0]

        // Check the transaction is well formed...
        val ledgerTx = exerciseTx.toLedgerTransaction(issuerNode.services)
        assert(ledgerTx.inputs.size == 1)
        assert(ledgerTx.outputs.size == 2)

        //Check IOU issues post exercising
        val iou = ledgerTx.outputs.single { it.data is IOUState }.data as IOUState
        val amount = iou.amount
        // Compare the cash assigned to the lender with the amount claimed is being settled by the borrower.
        assertEquals(
                amount,
                (inputOption.strikePrice - 3.DOLLARS))
        val command = ledgerTx.commands.requireSingleCommand<OptionContract.Commands>().value as OptionContract.Commands.Exercise
        assert(command.spot == spotPrice)
        // Check the transaction has been signed by the borrower.
        exerciseTx.verifySignaturesExcept(buyer.owningKey, DUMMY_NOTARY.owningKey)
    }

//    @Test
//    fun exerciseFlowReturnsTransactionSignedByBothParties() {
//        val stx = issueOptionToBuyer()
//        stx.verifyRequiredSignatures()
//    }
//
//    @Test
//    fun exerciseFlowRecordsTheTransactionInBothPartiesTxStorages() {
//        val stx = issueOptionToBuyer()
//
//        listOf(issuerNode, buyerNode).forEach {
//            val recordedTx = it.services.validatedTransactions.getTransaction(stx.id)
//            // The transaction with the correct ID is present in transaction storage.
//            assertNotNull(recordedTx)
//        }
//    }
//
//    @Test
//    fun exerciseFlowCanOnlyBeRunByOwner() {
//        val flow = OptionExerciseFlow.Initiator(createOption(issuer, buyer).linearId)
//        val future = issuerNode.services.startFlow(flow).resultFuture
//        mockNet.runNetwork()
//        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
//    }

    private fun issueOptionToBuyer(): SignedTransaction {
        val option = createOption(issuer, buyer)
        val flow = OptionIssueFlow.Initiator(option)
        val future = issuerNode.services.startFlow(flow).resultFuture
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
