package net.corda.option.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.TransactionType
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import net.corda.flows.SignTransactionFlow
import net.corda.option.GlobalVar
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.AttributeOf
import net.corda.option.datatypes.Spot
import net.corda.option.datatypes.Vol
import net.corda.option.oracle.service.Oracle
import net.corda.option.state.OptionState
import net.corda.option.types.OptionType
import java.time.Duration
import java.time.Instant
import java.util.*

object OptionRequestFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val state: OptionState) : FlowLogic<SignedTransaction>() {
        constructor(strike: Amount<Currency>,
                    expiryDate: Instant, underlying: String,
                    currency: Currency,
                    issuer: Party,
                    owner: Party,
                    optionType: OptionType) : this(OptionState(strike, expiryDate, underlying, currency, issuer, owner, optionType))

        companion object {
            object BUILDING : ProgressTracker.Step("Building and verifying transaction.")
            object SIGNING : ProgressTracker.Step("signing transaction.")
            object COLLECTING : ProgressTracker.Step("Collecting counterparty signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(BUILDING, SIGNING, COLLECTING, FINALISING)
        }

        override val progressTracker: ProgressTracker = Initiator.tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = BUILDING
            // Step 1. Get a reference to the notary service and oracle on our network
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val oracle = serviceHub.networkMapCache.getNodesWithService(Oracle.type).single()
            // **IMPORTANT:** Corda node services use their own key pairs, therefore we need to obtain the Party object for
            // the Oracle service as opposed to the node RUNNING the Oracle service.
            val oracleService = oracle.serviceIdentities(Oracle.type).single()

            // Step 2. Create a new issue command.
            // Remember that a command is a CommandData object and a list of CompositeKeys
            val issueCommand = Command(OptionContract.Commands.Issue(), state.participants.map { it.owningKey })

            // Step 3. Create a new TransactionBuilder object.
            val builder = TransactionType.General.Builder(notary)
            builder.addTimeWindow(Instant.now(), Duration.ofSeconds(60))

            // Step 4. Calculate price of option using volatility and spot obtained from Oracle
            val of = AttributeOf(state.underlying, GlobalVar().demoInstant)
            val vol: Vol = subFlow(QueryVol(oracleService, of))
            val spot: Spot = subFlow(QuerySpot(oracleService, of))
            // Assign spot to the option state so we can keep track of what it was at time of issue
            state.spot = spot.value
            val theoPrice = Amount(state.contract.calculatePremium(state, vol.value).toLong(), state.strike.token)

            // Step 5. Check we have enough cash to buy the requested option
            val cashBalance = serviceHub.vaultService.cashBalances[theoPrice.token] ?:
                    throw IllegalArgumentException("Buyer does not have ${theoPrice.token} to buy.")
            require(cashBalance >= theoPrice) { "Buyer has only $cashBalance but needs $theoPrice to buy." }

            // Step 6. Get some cash from the vault and add a spend to our transaction builder.
            serviceHub.vaultService.generateSpend(builder, theoPrice, state.issuer)

            // Step 7. Add the option as an output state, as well as a command to the transaction builder.
            builder.withItems(state, issueCommand)

            // Step 8. Verify and sign it with our KeyPair.
            builder.toWireTransaction().toLedgerTransaction(serviceHub).verify()
            progressTracker.currentStep = SIGNING
            val ptx = serviceHub.signInitialTransaction(builder)

            // Step 9. Collect the other party's signature using the SignTransactionFlow.
            progressTracker.currentStep = COLLECTING
            val stx = subFlow(CollectSignaturesFlow(ptx, COLLECTING.childProgressTracker()))

            // Step 10. Assuming no exceptions, we can now finalise the transaction.
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker())).single()
        }
    }

    @InitiatingFlow
    @InitiatedBy(OptionRequestFlow.Initiator::class)
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