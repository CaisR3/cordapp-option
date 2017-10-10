package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.option.DUMMY_OPTION_DATE
import net.corda.option.ORACLE_NAME
import net.corda.option.Stock
import net.corda.option.contract.OptionContract
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant
import java.util.function.Predicate

object OptionIssueFlow {

    /**
     * Purchases an option from the issuer, using the oracle to determine the correct price given the current spot
     * price and volatility.
     *
     * @property optionState the option to be purchased. Its [OptionState.spotPriceAtPurchase] will be updated as part
     *   of the flow.
     */
    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val optionState: OptionState) : FlowLogic<SignedTransaction>() {

        companion object {
            object SET_UP : ProgressTracker.Step("Initialising flow.")
            object QUERYING_THE_ORACLE : ProgressTracker.Step("Querying oracle for the current spot price and volatility.")
            object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
            object ADDING_CASH_PAYMENT : ProgressTracker.Step("Adding the cash to cover the premium.")
            object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
            object WE_SIGN : ProgressTracker.Step("Signing transaction.")
            object ORACLE_SIGNS : ProgressTracker.Step("Requesting oracle signature.")
            object OTHERS_SIGN : ProgressTracker.Step("Collecting counterparty signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(SET_UP, QUERYING_THE_ORACLE, BUILDING_THE_TX, ADDING_CASH_PAYMENT,
                    VERIFYING_THE_TX, WE_SIGN, OTHERS_SIGN, FINALISING)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SET_UP
            val notary = serviceHub.firstNotary()
            // In Corda v1.0, we identify oracles we want to use by name.
            val oracle = serviceHub.firstIdentityByName(ORACLE_NAME)

            // This flow can only be called by the option's current owner.
            require(optionState.owner == ourIdentity) { "Option issue flow must be initiated by the current buyer."}

            progressTracker.currentStep = QUERYING_THE_ORACLE
            val stockToCalculatePriceAndVolatilityOf = Stock(optionState.underlyingStock, DUMMY_OPTION_DATE)
            val (spotPrice, volatility) = subFlow(QueryOracle(oracle, stockToCalculatePriceAndVolatilityOf))
            // Store purchase spot price in state for future reference.
            optionState.spotPriceAtPurchase = spotPrice.value

            progressTracker.currentStep = BUILDING_THE_TX
            val requiredSigners = (optionState.participants + oracle).map { it.owningKey }
            val issueCommand = Command(OptionContract.Commands.Issue(spotPrice, volatility), requiredSigners)

            val builder = TransactionBuilder(notary)
                    .setTimeWindow(Instant.now(), Duration.ofSeconds(60))
                    .addOutputState(optionState, OPTION_CONTRACT_ID)
                    .addCommand(issueCommand)

            progressTracker.currentStep = ADDING_CASH_PAYMENT
            val optionPrice = Amount(OptionState.calculatePremium(optionState, volatility.value), optionState.strikePrice.token)
            Cash.generateSpend(serviceHub, builder, optionPrice, optionState.issuer)

            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = WE_SIGN
            val ptx = serviceHub.signInitialTransaction(builder)

            progressTracker.currentStep = ORACLE_SIGNS
            // For privacy reasons, we only want to expose to the oracle any commands of type
            // `OptionContract.Commands.Issue` that require its signature.
            val ftx = ptx.buildFilteredTransaction(Predicate {
                when (it) {
                    is Command<*> -> oracle.owningKey in it.signers && it.value is OptionContract.Commands.Issue
                    else -> false
                }
            })

            val oracleSignature = subFlow(RequestOracleSig(oracle, ftx))
            val ptxWithOracleSig = ptx.withAdditionalSignature(oracleSignature)

            progressTracker.currentStep = OTHERS_SIGN
            val issuerSession = initiateFlow(optionState.issuer)
            val stx = subFlow(CollectSignaturesFlow(ptxWithOracleSig, listOf(issuerSession), OTHERS_SIGN.childProgressTracker()))

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
                    // We check the oracle is a required signer. If so, we can trust the spot price and volatility data.
                    val oracle = serviceHub.firstIdentityByName(ORACLE_NAME)
                    stx.requiredSigningKeys.contains(oracle.owningKey)
                }
            }

            val stx = subFlow(flow)
            return waitForLedgerCommit(stx.id)
        }
    }
}