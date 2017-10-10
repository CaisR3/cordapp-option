package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.option.DUMMY_OPTION_DATE
import net.corda.option.ORACLE_NAME
import net.corda.option.Stock
import net.corda.option.contract.IOUContract
import net.corda.option.contract.OptionContract
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant
import java.util.function.Predicate

object OptionExerciseFlow {

    /**
     * Exercises the option to convert it into an IOU against the issuer. The value of the IOU is equal to the moneyness of
     * the option at the time of exercise.
     *
     * @property linearId the ID of the option to be exercised.
     */
    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {
        companion object {
            object SET_UP : ProgressTracker.Step("Initialising flow.")
            object RETRIEVING_THE_INPUTS : ProgressTracker.Step("We retrieve the option to exercise from the vault.")
            object QUERYING_THE_ORACLE : ProgressTracker.Step("Querying oracle for the current spot price.")
            object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
            object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
            object WE_SIGN : ProgressTracker.Step("signing transaction.")
            object ORACLE_SIGNS : ProgressTracker.Step("Requesting oracle signature.")
            object FINALISING : ProgressTracker.Step("Finalising transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(SET_UP, QUERYING_THE_ORACLE, BUILDING_THE_TX, RETRIEVING_THE_INPUTS,
                    VERIFYING_THE_TX, WE_SIGN, ORACLE_SIGNS, FINALISING)
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SET_UP
            val notary = serviceHub.firstNotary()
            // In Corda v1.0, we identify oracles we want to use by name.
            val oracle = serviceHub.firstIdentityByName(ORACLE_NAME)

            progressTracker.currentStep = RETRIEVING_THE_INPUTS
            val stateAndRef = serviceHub.getStateAndRefByLinearId<OptionState>(linearId)
            val inputState = stateAndRef.state.data

            // This flow can only be called by the option's current owner.
            require(inputState.owner == ourIdentity) { "Option exercise flow must be initiated by the current owner."}

            progressTracker.currentStep = QUERYING_THE_ORACLE
            val stockToQueryPriceOf = Stock(inputState.underlyingStock, DUMMY_OPTION_DATE)
            val (spotPrice, _) = subFlow(QueryOracle(oracle, stockToQueryPriceOf))

            progressTracker.currentStep = BUILDING_THE_TX
            val profit = OptionState.calculateMoneyness(inputState.strikePrice, spotPrice.value, inputState.optionType)
            val iouState = IOUState(profit, inputState.owner, inputState.issuer, linearId = inputState.linearId)

            val issueCommand = Command(IOUContract.Commands.Issue(), inputState.owner.owningKey)
            // By listing the oracle here, we make the oracle a required signer.
            val exerciseCommand = Command(OptionContract.Commands.Exercise(spotPrice), listOf(oracle.owningKey, inputState.owner.owningKey))

            // Add the state and the command to the builder.
            val builder = TransactionBuilder(notary)
                    .setTimeWindow(Instant.now(), Duration.ofSeconds(60))
                    .addInputState(stateAndRef)
                    .addOutputState(iouState, IOUContract.IOU_CONTRACT_ID)
                    .addCommand(exerciseCommand)
                    .addCommand(issueCommand)

            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = WE_SIGN
            val ptx = serviceHub.signInitialTransaction(builder)

            progressTracker.currentStep = ORACLE_SIGNS
            // For privacy reasons, we only want to expose to the oracle any commands of type
            // `OptionContract.Commands.Exercise` that require its signature.
            val ftx = ptx.buildFilteredTransaction(Predicate {
                when (it) {
                    is Command<*> -> oracle.owningKey in it.signers && it.value is OptionContract.Commands.Exercise
                    else -> false
                }
            })

            val oracleSignature = subFlow(SignTx(oracle, ftx))
            val stx = ptx.withAdditionalSignature(oracleSignature)

            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(stx))
        }
    }
}