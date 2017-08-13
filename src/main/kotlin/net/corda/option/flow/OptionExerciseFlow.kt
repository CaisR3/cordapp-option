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
import net.corda.option.GlobalVar
import net.corda.option.contract.IOUContract
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.Spot
import net.corda.option.datatypes.AttributeOf
import net.corda.option.oracle.service.Oracle
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import java.time.Duration
import java.time.Instant

object OptionExerciseFlow {

    @InitiatingFlow     // This flow can be started by the node.
    @StartableByRPC // Annotation to allow this flow to be started via RPC.
    //allow time to be overridden for testing
    class Initiator(val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {
        // Progress tracker boilerplate.
        companion object {
            object INITIALISING : ProgressTracker.Step("Initialising flow.")
            object QUERYING : ProgressTracker.Step("Querying Oracle for an nth prime.")
            object BUILDING_AND_VERIFYING : ProgressTracker.Step("Building and verifying transaction.")
            object ORACLE_SIGNING : ProgressTracker.Step("Requesting Oracle signature.")
            object SIGNING : ProgressTracker.Step("signing transaction.")
            object COLLECTING : ProgressTracker.Step("Collecting counterparty signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(INITIALISING, QUERYING, BUILDING_AND_VERIFYING, ORACLE_SIGNING, SIGNING, FINALISING)
        }
        override val progressTracker: ProgressTracker = Initiator.tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            // Get references to all required parties.
            progressTracker.currentStep = INITIALISING
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            // We get the oracle reference by using the ServiceType definition defined in the base CorDapp.
            val oracle = serviceHub.networkMapCache.getNodesWithService(Oracle.type).single()
            // **IMPORTANT:** Corda node services use their own key pairs, therefore we need to obtain the Party object for
            // the Oracle service as opposed to the node RUNNING the Oracle service.
            val oracleService = oracle.serviceIdentities(Oracle.type).single()
            // The calling node's identity.
            val me = serviceHub.myInfo.legalIdentity

            val states = serviceHub.vaultService.linearHeadsOfType<OptionState>()
            val stateAndRef = states[linearId] ?: throw IllegalArgumentException("OptionState with linearId $linearId not found.")
            val inputState = stateAndRef.state.data

            // This flow can only be called by the current owner
            require(inputState.owner == me) { "Option exercise flow must be initiated by the current owner"}

            // Query the Oracle to get specified spot.
            progressTracker.currentStep = QUERYING
            //TODO remove hardcoding

            //serviceHub.cordaService(NodeStocks.Oracle::class.java)

            val spotOf = AttributeOf(inputState.underlying, GlobalVar().demoInstant)
            val spot: Spot = subFlow(QuerySpot(oracleService, spotOf))

            // Create a new transaction using the data from the Oracle.
            progressTracker.currentStep = BUILDING_AND_VERIFYING

            // Create output Option and IOU state
            val outputOptionState = inputState.exercise(spot.value)
            val profit = OptionContract().calculateMoneyness(outputOptionState.strike, outputOptionState.spot, outputOptionState.optionType)
            //use same linear id to keep track of the fact this IOU is linked to the option
            val iouState = IOUState(profit, inputState.owner, inputState.issuer, linearId = inputState.linearId)

            // Build our command.
            // NOTE: The command requires the public key of the oracle, hence we need the signature from the oracle over
            // this transaction.
            val exerciseCommand = Command(OptionContract.Commands.Exercise(spot), listOf(oracleService.owningKey, inputState.owner.owningKey))
            val issueCommand = Command(IOUContract.Commands.Issue(), inputState.owner.owningKey)

            // Add the state and the command to the builder.
            val builder = TransactionType.General.Builder(notary)
            builder.addTimeWindow(Instant.now(), Duration.ofSeconds(60))
            builder.withItems(stateAndRef, outputOptionState, iouState, exerciseCommand, issueCommand)

            // Verify the transaction.
            builder.toWireTransaction().toLedgerTransaction(serviceHub).verify()

            // Build a filtered transaction for the Oracle to sign over.
            // We only want to expose the exercise command if the specified Oracle is a signer.
            val ftx = builder.toWireTransaction().buildFilteredTransaction ({
                when (it) {
                    is Command -> oracleService.owningKey in it.signers && it.value is OptionContract.Commands.Exercise
                    else -> false
                }
            })

            //(oracleService.owningKey in this .signers) // && it.value is OptionContract.Commands.Issue

            // Get a signature from the Oracle and add it to the transaction.
            progressTracker.currentStep = ORACLE_SIGNING
            // Get a signature from the Oracle over the Merkle root of the transaction.
            val oracleSignature = subFlow(SignTx(oracle.legalIdentity, ftx))
            // Append the oracle's signature to the transaction and convert the builder to a SignedTransaction.
            // We use the 'checkSufficientSignatures = false' as we haven't collected all the signatures yet.
            val ptx = builder.addSignatureUnchecked(oracleSignature).toSignedTransaction(checkSufficientSignatures = false)

            // Add our signature.
            progressTracker.currentStep = SIGNING
            // Generate the signature then add it to the transaction.
            val mySignature = serviceHub.createSignature(ptx, me.owningKey)
            val stx = ptx + mySignature

            // Finalise.
            // We do this by calling finality flow. The transaction will be broadcast to all parties listed in 'participants'.
            progressTracker.currentStep = FINALISING
            val result = subFlow(FinalityFlow(stx)).single()

            return result
        }
    }

    @InitiatingFlow
    @InitiatedBy(OptionExerciseFlow.Initiator::class)
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