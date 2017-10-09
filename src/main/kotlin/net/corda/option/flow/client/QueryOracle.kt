package net.corda.option.flow.client

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import net.corda.option.SpotPrice
import net.corda.option.Stock
import net.corda.option.Volatility

/** Called by the client to request a stock's spot price and volatility at a point in time from an oracle. */
@InitiatingFlow
class QueryOracle(val oracle: Party, val stock: Stock) : FlowLogic<Pair<SpotPrice, Volatility>>() {
    @Suspendable override fun call(): Pair<SpotPrice, Volatility> {
        val oracleSession = initiateFlow(oracle)
        return oracleSession.sendAndReceive<Pair<SpotPrice, Volatility>>(stock).unwrap { it }
    }
}