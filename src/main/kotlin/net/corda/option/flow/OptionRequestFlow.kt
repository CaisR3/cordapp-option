package net.corda.option.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import net.corda.finance.schemas.CashSchemaV1
import net.corda.option.DEMO_INSTANT
import net.corda.option.ORACLE_NAME
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.AttributeOf
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
            object BUILDING_THE_TX : ProgressTracker.Step("Building transaction.")
            object VERIFYING_THE_TX : ProgressTracker.Step("Verifying transaction.")
            object SIGNING : ProgressTracker.Step("signing transaction.")
            object OTHERS_SIGN : ProgressTracker.Step("Collecting counterparty signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(SET_UP, QUERYING_THE_ORACLE, BUILDING_THE_TX, VERIFYING_THE_TX, OTHERS_SIGN,
                    FINALISING)
        }

        override val progressTracker: ProgressTracker = Initiator.tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SET_UP
            // Step 1. Get a reference to the notary service and oracle on our network
            val notary = serviceHub.firstNotary()
            // In Corda v1.0, we identify oracles we want to use by name.
            val oracle = serviceHub.firstIdentityByName(ORACLE_NAME)

            progressTracker.currentStep = QUERYING_THE_ORACLE
            // Calculate price of option using volatility and spot obtained from oracle.
            val of = AttributeOf(state.underlying, DEMO_INSTANT)
            val volatility = subFlow(QueryVol(oracle, of))
            val spot = subFlow(QuerySpot(oracle, of))
            // Assign spot to the option state so we can keep track of what it was at time of issue
            state.spot = spot.value
            val premium = Amount(OptionContract.calculatePremium(state, volatility.value).toLong(), state.strike.token)

            progressTracker.currentStep = BUILDING_THE_TX
            val issueCommand = Command(OptionContract.Commands.Issue(), state.participants.map { it.owningKey })

            // Step 3. Create a new TransactionBuilder object.
            val builder = TransactionBuilder(notary)
            builder.setTimeWindow(Instant.now(), Duration.ofSeconds(60))

            // Step 5. Check we have enough cash to buy the requested option
            val cashBalance = serviceHub.getCashBalance(premium.token)
            require(cashBalance >= premium) { "Buyer has only $cashBalance but needs $premium to buy." }

            // Step 6. Get some cash from the vault and add a spend to our transaction builder.
            Cash.generateSpend(serviceHub, builder, premium, state.issuer)

            builder.withItems(state, issueCommand)

            progressTracker.currentStep = VERIFYING_THE_TX
            builder.verify(serviceHub)

            progressTracker.currentStep = SIGNING
            val ptx = serviceHub.signInitialTransaction(builder)

            // Step 9. Collect the other party's signature using the SignTransactionFlow.
            progressTracker.currentStep = OTHERS_SIGN
            val counterparties = state.participants
            val counterpartySessions = counterparties.map { initiateFlow(it as Party) }
            val stx = subFlow(CollectSignaturesFlow(ptx, counterpartySessions, OTHERS_SIGN.childProgressTracker()))

            // Step 10. Assuming no exceptions, we can now finalise the transaction.
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker()))
        }
    }

    @InitiatingFlow
    @InitiatedBy(OptionRequestFlow.Initiator::class)
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