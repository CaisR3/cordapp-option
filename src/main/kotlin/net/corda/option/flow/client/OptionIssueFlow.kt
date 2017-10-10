package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalances
import net.corda.option.DUMMY_OPTION_DATE
import net.corda.option.ORACLE_NAME
import net.corda.option.Stock
import net.corda.option.contract.OptionContract
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant

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
            object CALCULATING_PREMIUM : ProgressTracker.Step("Calculating the option's premium.")
            object CHECKING_CASH_BALANCES : ProgressTracker.Step("Checking whether we have the cash to cover the premium.")
            object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
            object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
            object WE_SIGN : ProgressTracker.Step("Signing transaction.")
            object OTHERS_SIGN : ProgressTracker.Step("Collecting counterparty signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(SET_UP, QUERYING_THE_ORACLE, CALCULATING_PREMIUM, BUILDING_THE_TX,
                    CHECKING_CASH_BALANCES, VERIFYING_THE_TX, WE_SIGN, OTHERS_SIGN, FINALISING)
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

            progressTracker.currentStep = CALCULATING_PREMIUM
            val optionPrice = Amount(OptionState.calculatePrice(optionState, volatility.value), optionState.strikePrice.token)

            progressTracker.currentStep = BUILDING_THE_TX
            val requiredSigners = optionState.participants.map { it.owningKey }
            val issueCommand = Command(OptionContract.Commands.Issue(), requiredSigners)

            val builder = TransactionBuilder(notary)
                    .setTimeWindow(Instant.now(), Duration.ofSeconds(60))
                    .addOutputState(optionState, OPTION_CONTRACT_ID)
                    .addCommand(issueCommand)

            progressTracker.currentStep = CHECKING_CASH_BALANCES
            val cashBalanceOfCurrency = serviceHub.getCashBalances()[optionPrice.token] ?:
                    throw IllegalArgumentException("Buyer does not have any ${optionPrice.token} to purchase the option.")
            require(cashBalanceOfCurrency >= optionPrice) { "Buyer has only $cashBalanceOfCurrency but needs $optionPrice to buy." }
            // We add cash to the builder to cover the option's purchase price.
            Cash.generateSpend(serviceHub, builder, optionPrice, optionState.issuer)

            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = WE_SIGN
            val ptx = serviceHub.signInitialTransaction(builder)

            progressTracker.currentStep = OTHERS_SIGN
            val participantsExceptUs = optionState.participants.filter { it != ourIdentity }
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