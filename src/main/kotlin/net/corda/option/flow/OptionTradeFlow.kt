package net.corda.option.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.linearHeadsOfType
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import net.corda.flows.SignTransactionFlow
import net.corda.option.contract.OptionContract
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant

object OptionTradeFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val linearId: UniqueIdentifier, val newOwner: Party) : FlowLogic<SignedTransaction>() {

        override val progressTracker: ProgressTracker = Initiator.tracker()

        companion object {
            object PREPARATION : ProgressTracker.Step("Obtaining option from vault.")
            object BUILDING : ProgressTracker.Step("Building and verifying transaction.")
            object SIGNING : ProgressTracker.Step("signing transaction.")
            object COLLECTING : ProgressTracker.Step("Collecting counterparty signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(PREPARATION, BUILDING, SIGNING, COLLECTING, FINALISING)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = PREPARATION
            val states = serviceHub.vaultService.linearHeadsOfType<OptionState>()
            val stateAndRef = states[linearId] ?: throw IllegalArgumentException("OptionState with linearId $linearId not found.")
            val inputState = stateAndRef.state.data

            // This flow can only be initiated by the current owner
            require(serviceHub.myInfo.legalIdentity == inputState.owner) { "Option transfer can only be initiated by the current owner." }

            progressTracker.currentStep = BUILDING
            // Create new state with new owner
            val outputState = inputState.withNewOwner(newOwner).second

            // Create a new trade command.
            // Gather the current participants along with the new owner
            val parties = (inputState.participants + newOwner).map { it.owningKey }
            val tradeCommand = Command(OptionContract.Commands.Trade(), parties)

            // Get a reference to the notary service on our network and our key pair.
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val builder = TransactionType.General.Builder(notary)
            builder.addTimeWindow(Instant.now(), Duration.ofSeconds(60))

            // Add the option as an output state, as well as a command to the transaction builder.
            builder.withItems(stateAndRef, outputState, tradeCommand)

            // Verify and sign it with our KeyPair.
            builder.toWireTransaction().toLedgerTransaction(serviceHub).verify()
            progressTracker.currentStep = SIGNING
            val ptx = serviceHub.signInitialTransaction(builder)

            // Collect the other party's signature using the SignTransactionFlow.
            progressTracker.currentStep = COLLECTING
            val stx = subFlow(CollectSignaturesFlow(ptx, COLLECTING.childProgressTracker()))

            // Assuming no exceptions, we can now finalise the transaction.
            progressTracker.currentStep = FINALISING
            // For now, we operate under the assumption that the issuer needs to sign all trade deals
            return subFlow(FinalityFlow(stx, setOf(inputState.issuer, inputState.owner, newOwner))).single()
        }
    }

    @InitiatingFlow
    @InitiatedBy(OptionTradeFlow.Initiator::class)
    class Responder(val otherParty: Party) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val flow = object : SignTransactionFlow(otherParty) {
                @Suspendable
                override fun checkTransaction(stx: SignedTransaction) {
                    // TODO: Add some checking.
                }
            }

            val stx = subFlow(flow)

            return waitForLedgerCommit(stx.id)
        }
    }
}