package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.option.contract.OptionContract
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant

// TODO: Describe this flow.
object OptionIssueFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val state: OptionState) : FlowLogic<SignedTransaction>() {

        companion object {
            object SET_UP : ProgressTracker.Step("Initialising flow.")
            object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
            object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
            object WE_SIGN : ProgressTracker.Step("Signing transaction.")
            object OTHERS_SIGN : ProgressTracker.Step("Collecting counterparty signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(SET_UP, BUILDING_THE_TX, VERIFYING_THE_TX, WE_SIGN, OTHERS_SIGN, FINALISING)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SET_UP
            val notary = serviceHub.firstNotary()

            progressTracker.currentStep = BUILDING_THE_TX
            val issueCommand = Command(OptionContract.Commands.Issue(), state.participants.map { it.owningKey })

            val builder = TransactionBuilder(notary)
                    .setTimeWindow(Instant.now(), Duration.ofSeconds(60))
                    .addOutputState(state, OPTION_CONTRACT_ID)
                    .addCommand(issueCommand)

            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = WE_SIGN
            val ptx = serviceHub.signInitialTransaction(builder)

            progressTracker.currentStep = OTHERS_SIGN
            val participantsExceptUs = state.participants.filter { it != ourIdentity }
            val participantSessions = participantsExceptUs.map { initiateFlow(it) }
            val stx = subFlow(CollectSignaturesFlow(ptx, participantSessions, OTHERS_SIGN.childProgressTracker()))

            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker()))
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