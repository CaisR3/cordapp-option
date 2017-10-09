package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import net.corda.option.DEMO_INSTANT
import net.corda.option.ORACLE_NAME
import net.corda.option.Stock
import net.corda.option.contract.OptionContract
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant

object OptionRequestFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val state: OptionState) : FlowLogic<SignedTransaction>() {

        companion object {
            object SET_UP : ProgressTracker.Step("Initialising flow.")
            object QUERYING_THE_ORACLE : ProgressTracker.Step("Querying oracle.")
            object CHECKING_SUFFICIENT_CASH : ProgressTracker.Step("Checking we have the cash to cover the premium.")
            object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
            object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
            object WE_SIGN : ProgressTracker.Step("Signing transaction.")
            object OTHERS_SIGN : ProgressTracker.Step("Collecting counterparty signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(SET_UP, QUERYING_THE_ORACLE, CHECKING_SUFFICIENT_CASH, BUILDING_THE_TX,
                    VERIFYING_THE_TX, WE_SIGN, OTHERS_SIGN, FINALISING)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SET_UP
            val notary = serviceHub.firstNotary()
            // In Corda v1.0, we identify oracles we want to use by name.
            val oracle = serviceHub.firstIdentityByName(ORACLE_NAME)

            progressTracker.currentStep = QUERYING_THE_ORACLE
            val stockToQueryPriceAndVolatilityOf = Stock(state.underlyingStock, DEMO_INSTANT)
            val (spotPrice, volatility) = subFlow(QueryOracle(oracle, stockToQueryPriceAndVolatilityOf))
            // Assign spot-price to the OptionState so we can keep track of what it was at the time of issue.
            state.spotPrice = spotPrice.value
            val premium = Amount(OptionContract.calculatePremium(state, volatility.value).toLong(), state.strike.token)

            progressTracker.currentStep = CHECKING_SUFFICIENT_CASH
            val cashBalance = serviceHub.getCashBalance(premium.token)
            require(cashBalance >= premium) { "Buyer has only $cashBalance but needs $premium to buy." }

            progressTracker.currentStep = BUILDING_THE_TX
            val issueCommand = Command(OptionContract.Commands.Issue(), state.participants.map { it.owningKey })

            val builder = TransactionBuilder(notary)
                    .setTimeWindow(Instant.now(), Duration.ofSeconds(60))
                    .addOutputState(state, OptionContract.OPTION_CONTRACT_ID)
                    .addCommand(issueCommand)

            // Modifies the builder in place to add the required cash inputs and outputs.
            Cash.generateSpend(serviceHub, builder, premium, state.issuer)

            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = WE_SIGN
            val ptx = serviceHub.signInitialTransaction(builder)

            progressTracker.currentStep = OTHERS_SIGN
            val counterparties = state.participants
            val counterpartySessions = counterparties.map { initiateFlow(it) }
            val stx = subFlow(CollectSignaturesFlow(ptx, counterpartySessions, OTHERS_SIGN.childProgressTracker()))

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