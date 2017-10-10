package net.corda.option.flow.oracle

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.option.Stock
import net.corda.option.flow.client.QueryOracle
import net.corda.option.service.Oracle

/** Called by the oracle to provide a stock's spot price to a client. */
@InitiatedBy(QueryOracle::class)
class QueryOracleHandler(private val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    companion object {
        object RECEIVING : ProgressTracker.Step("Received stock to provide the spot price for.")
        object RETRIEVING : ProgressTracker.Step("Retrieving the spot price.")
        object SENDING : ProgressTracker.Step("Sending spot price to counterparty.")
    }

    override val progressTracker = ProgressTracker(RECEIVING, RETRIEVING, SENDING)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = RECEIVING
        val stock = counterpartySession.receive<Stock>().unwrap { it }

        progressTracker.currentStep = RETRIEVING
        val spotPriceAndVolatility = try {
            val spotPrice = serviceHub.cordaService(Oracle::class.java).querySpot(stock)
            val volatility = serviceHub.cordaService(Oracle::class.java).queryVolatility(stock)
            Pair(spotPrice, volatility)
        } catch (e: Exception) {
            throw FlowException(e)
        }

        progressTracker.currentStep = SENDING
        counterpartySession.send(spotPriceAndVolatility)
    }
}