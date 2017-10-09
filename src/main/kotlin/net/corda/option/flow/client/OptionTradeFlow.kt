package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant

object OptionTradeFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val linearId: UniqueIdentifier, val newOwner: Party) : FlowLogic<SignedTransaction>() {

        override val progressTracker: ProgressTracker = tracker()

        companion object {
            object RETRIEVING_THE_INPUTS : ProgressTracker.Step("We retrieve the option to exercise from the vault.")
            object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
            object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
            object WE_SIGN : ProgressTracker.Step("signing transaction.")
            object OTHERS_SIGN : ProgressTracker.Step("Requesting oracle signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING : ProgressTracker.Step("Finalising transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(RETRIEVING_THE_INPUTS, BUILDING_THE_TX, VERIFYING_THE_TX, WE_SIGN, OTHERS_SIGN, FINALISING)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = RETRIEVING_THE_INPUTS
            val stateAndRef = serviceHub.getStateAndRefByLinearId<OptionState>(linearId)
            val inputState = stateAndRef.state.data

            // This flow can only be initiated by the current owner
            require(ourIdentity == inputState.owner) { "Option transfer can only be initiated by the current owner." }

            progressTracker.currentStep = BUILDING_THE_TX
            // Create new state with new owner
            val (tradeCommandData, outputState) = inputState.withNewOwner(newOwner)

            // Create a new trade command.
            // Gather the current participants along with the new owner
            val parties = inputState.participants + newOwner
            val partyKeys = parties.map { it.owningKey }
            val tradeCommand = Command(tradeCommandData, partyKeys)

            // Get a reference to the notary service on our network and our key pair.
            val notary = serviceHub.firstNotary()
            val builder = TransactionBuilder(notary)
            builder.setTimeWindow(Instant.now(), Duration.ofSeconds(60))

            // Add the option as an output state, as well as a command to the transaction builder.
            builder.withItems(stateAndRef, outputState, tradeCommand)

            // Verify and sign it with our KeyPair.
            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = WE_SIGN
            val ptx = serviceHub.signInitialTransaction(builder)

            progressTracker.currentStep = OTHERS_SIGN
            val counterpartySessions = parties.map { initiateFlow(it) }
            val stx = subFlow(CollectSignaturesFlow(ptx, counterpartySessions, OTHERS_SIGN.childProgressTracker()))

            // Assuming no exceptions, we can now finalise the transaction.
            progressTracker.currentStep = FINALISING
            // For now, we operate under the assumption that the issuer needs to sign all trade deals
            return subFlow(FinalityFlow(stx, setOf(inputState.issuer, inputState.owner, newOwner)))
        }
    }

    @InitiatingFlow
    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val flow = object : SignTransactionFlow(counterpartySession) {
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