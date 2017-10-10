package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.option.contract.OptionContract
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant

object OptionTradeFlow {

    /**
     * Transfers an option to a new owner. The existing owner gets no payment in return.
     *
     * @property linearId the ID of the option to be transferred.
     * @property newOwner the owner the option is being transferred to.
     */
    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val linearId: UniqueIdentifier, private val newOwner: Party) : FlowLogic<SignedTransaction>() {

        override val progressTracker: ProgressTracker = tracker()

        companion object {
            object SET_UP : ProgressTracker.Step("Initialising flow.")
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

            fun tracker() = ProgressTracker(SET_UP, RETRIEVING_THE_INPUTS, BUILDING_THE_TX, VERIFYING_THE_TX, WE_SIGN,
                    OTHERS_SIGN, FINALISING)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SET_UP
            val notary = serviceHub.firstNotary()

            progressTracker.currentStep = RETRIEVING_THE_INPUTS
            val stateAndRef = serviceHub.getStateAndRefByLinearId<OptionState>(linearId)
            val inputState = stateAndRef.state.data

            // This flow can only be initiated by the option's current owner.
            require(ourIdentity == inputState.owner) { "Option transfer can only be initiated by the current owner." }

            progressTracker.currentStep = BUILDING_THE_TX
            val outputState = inputState.withNewOwner(newOwner)

            val requiredSigners = inputState.participants + newOwner
            val tradeCommand = Command(OptionContract.Commands.Trade(), requiredSigners.map { it.owningKey })

            val builder = TransactionBuilder(notary)
                    .setTimeWindow(Instant.now(), Duration.ofSeconds(60))
                    .addInputState(stateAndRef)
                    .addOutputState(outputState, OPTION_CONTRACT_ID)
                    .addCommand(tradeCommand)

            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = WE_SIGN
            val ptx = serviceHub.signInitialTransaction(builder)

            progressTracker.currentStep = OTHERS_SIGN
            val counterpartySessions = requiredSigners.filter { it != ourIdentity }.map { initiateFlow(it) }
            val stx = subFlow(CollectSignaturesFlow(ptx, counterpartySessions, OTHERS_SIGN.childProgressTracker()))

            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(stx))
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