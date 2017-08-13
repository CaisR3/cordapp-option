package com.option.flow

import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.getOrThrow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.option.contract.OptionContract
import net.corda.option.state.IOUState
import net.corda.testing.node.MockNetwork
import net.corda.option.getOption
import net.corda.option.oracle.service.Oracle
import net.corda.option.flow.*
import net.corda.option.oracle.flow.*
import net.corda.option.datatypes.*
import net.corda.option.state.OptionState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionExerciseFlowTests {
    lateinit var net: MockNetwork
    lateinit var a: MockNetwork.MockNode
    lateinit var b: MockNetwork.MockNode
    lateinit var c: MockNetwork.MockNode
    lateinit var d: MockNetwork.MockNode

    @Before
    fun setup() {
        net = MockNetwork()
        val nodes = net.createSomeNodes(3)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]
        c = nodes.partyNodes[2]
        d = net.createNode(nodes.mapNode.info.address, advertisedServices = ServiceInfo(Oracle.type))

        nodes.partyNodes.forEach {
            it.registerInitiatedFlow(OptionIssueFlow.Responder::class.java)
            it.registerInitiatedFlow(OptionTradeFlow.Responder::class.java)
            it.registerInitiatedFlow(OptionExerciseFlow.Responder::class.java)
        }
        // run node d as oracle
        d.registerInitiatedFlow(QuerySpotHandler::class.java)
        d.registerInitiatedFlow(SignHandler::class.java)
        d.installCordaService(Oracle::class.java)
        net.runNetwork()
    }

    @After
    fun tearDown() {
        net.stopNodes()
    }

    private fun issueOption(): SignedTransaction {
        val option = getOption(a, b)
        val flow = OptionIssueFlow.Initiator(option)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        return future.getOrThrow()
    }

    /**
     * Issue some on-ledger cash to ourselves, we need to do this before we can Settle an Option.
     */
    private fun issueCash(amount: Amount<Currency>): Cash.State {
        val flow = SelfIssueCashFlow(amount)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        return future.getOrThrow()
    }

    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val stx = issueOption()
        //issueCash(50.DOLLARS)
        val time = Instant.parse("2017-07-03T10:15:30.00Z")
        val option = getOption(a, b)
        val spotValue = 3.DOLLARS
        val spot = Spot(AttributeOf(option.underlying, time), spotValue)

        val inputOption = stx.tx.outputs.single().data as OptionState
        val flow = OptionExerciseFlow.Initiator(option.linearId)
        val future = b.services.startFlow(flow).resultFuture
        net.runNetwork()
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
        exerciseResult.verifySignatures(b.info.legalIdentity.owningKey, DUMMY_NOTARY.owningKey)
    }

    @Test
    fun exerciseFlowCanOnlyBeRunByOwner() {
        val flow = OptionExerciseFlow.Initiator(getOption(a, b).linearId)
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }
}
