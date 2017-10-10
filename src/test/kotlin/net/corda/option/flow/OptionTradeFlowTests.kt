package net.corda.option.flow

import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.option.DUMMY_LINEAR_ID
import net.corda.option.ORACLE_NAME
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
        setCordappPackages("net.corda.option.contract")

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
//    fun tradeFlowRecordsATransactionInAllPartiesTxStorages() {
//        issueOptionToBuyerA()
//        val stx = tradeOptionWithBuyerB()
//
//        listOf(issuerNode, buyerANode, buyerBNode).forEach {
//            val recordedTx = it.services.validatedTransactions.getTransaction(stx.id)
//            // The transaction with the correct ID is present in transaction storage.
//            assertNotNull(recordedTx)
//
//            // Transaction has a single input.
//            assertEquals(1, recordedTx!!.tx.inputs.size)
//
//            // Transaction has a single OptionState output.
//            assertEquals(1, recordedTx.tx.outputs.size)
//            assertEquals(1, recordedTx.tx.outputsOfType<OptionState>().size)
//
//            // Transaction has a single command with the correct attributes.
//            assertEquals(1, recordedTx.tx.commands.size)
//            val command = recordedTx.tx.commands.single()
//            assert(command.value is OptionContract.Commands.Trade)
//            listOf(issuer, buyerA, buyerB).forEach {
//                assert(command.signers.contains(it.owningKey))
//            }
//
//            // Transaction is fully signed.
//            recordedTx.verifyRequiredSignatures()
//        }
//    }

    @Test
    fun tradeFlowRecordsTheOptionInAllPartiesVaults() {
        issueOptionToBuyerA()
        tradeOptionWithBuyerB()

        // An OptionState is present in the vaults of the issuer and current owner.
        listOf(issuerNode, buyerBNode).forEach {
            it.database.transaction {
                val options = it.services.vaultService.queryBy<OptionState>().states
                // An OptionState is present in the vault.
                assertEquals(1, options.size)
                val recordedOption = options.single().state.data
                assertEquals(createOption(issuer, buyerB), recordedOption)
            }
        }

        // No OptionStates are present in the vaults of the previous owner.
        buyerANode.database.transaction {
            val options = buyerANode.services.vaultService.queryBy<OptionState>().states
            assertEquals(0, options.size)
        }
    }

//    @Test
//    fun flowCanOnlyBeRunByCurrentOwner() {
//        issueOptionToBuyerA()
//        val flow = OptionTradeFlow.Initiator(DUMMY_LINEAR_ID, buyerB)
//        // We are running the flow from the issuer, who doesn't currently own the option.
//        val future = issuerNode.services.startFlow(flow).resultFuture
//        mockNet.runNetwork()
//        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
//    }

//    @Test
//    fun optionCannotBeTransferredToSameParty() {
//        issueOptionToBuyerA()
//        val flow = OptionTradeFlow.Initiator(DUMMY_LINEAR_ID, buyerA)
//        // Buyer A already owns the option that they're trying to transfer to themselves.
//        val future = buyerANode.services.startFlow(flow).resultFuture
//        mockNet.runNetwork()
//        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
//    }

    /** Issues an option from the issuer to buyer A. */
    private fun issueOptionToBuyerA(): SignedTransaction {
        val option = createOption(issuer, buyerA)
        val flow = OptionIssueFlow.Initiator(option)
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }

    /** Transfers the option from buyer A to buyer B. */
    private fun tradeOptionWithBuyerB(): SignedTransaction {
        val flow = OptionTradeFlow.Initiator(DUMMY_LINEAR_ID, buyerB)
        val future = buyerANode.services.startFlow(flow).resultFuture
        mockNet.runNetwork()
        return future.getOrThrow()
    }
}