package net.corda.option.oracle.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.identity.Party
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.option.flow.SignTx
import net.corda.option.oracle.service.Oracle

// The Service side flow to handle Oracle signing requests.
@InitiatedBy(SignTx::class)
class SignHandler(val otherParty: Party) : FlowLogic<Unit>() {
    companion object {
        object RECEIVED : ProgressTracker.Step("Received sign request")
        object SENDING : ProgressTracker.Step("Sending sign response")
    }

    override val progressTracker = ProgressTracker(RECEIVED, SENDING)

    init {
        progressTracker.currentStep = RECEIVED
    }

    @Suspendable
    override fun call() {
        val request = receive<FilteredTransaction>(otherParty).unwrap { it }
        progressTracker.currentStep = SENDING
        try {
            val response = serviceHub.cordaService(Oracle::class.java).sign(request)
            send(otherParty, response)
        } catch (e: Exception) {
            throw FlowException(e)
        }
    }
}