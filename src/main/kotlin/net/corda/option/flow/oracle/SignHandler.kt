package net.corda.option.flow.oracle

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.option.flow.client.SignTx
import net.corda.option.service.Oracle

/** Called by the oracle to provide a signature over a transaction. */
@InitiatedBy(SignTx::class)
class SignHandler(private val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    companion object {
        object RECEIVING : ProgressTracker.Step("Received filtered transaction to sign over.")
        object SIGNING : ProgressTracker.Step("Signing over the filtered transaction.")
        object SENDING : ProgressTracker.Step("Sending signature to counterparty.")
    }

    override val progressTracker = ProgressTracker(RECEIVING, SIGNING, SENDING)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = RECEIVING
        val ftx = counterpartySession.receive<FilteredTransaction>().unwrap { it }

        progressTracker.currentStep = SIGNING
        val signature = try {
            serviceHub.cordaService(Oracle::class.java).sign(ftx)
        } catch (e: Exception) {
            throw FlowException(e)
        }

        progressTracker.currentStep = SENDING
        counterpartySession.send(signature)
    }
}