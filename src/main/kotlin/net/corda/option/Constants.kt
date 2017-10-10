package net.corda.option

import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.finance.USD
import java.time.Instant

val ORACLE_NAME = CordaX500Name("PartyD", "New York","US")
val DUMMY_OPTION_DATE = Instant.parse("2017-07-03T10:15:30.00Z")!!
val OPTION_CURRENCY = USD
val RISK_FREE_RATE = 0.01
val COMPANY_1 = "IBM"
val COMPANY_2 = "R3"
val KNOWN_SPOTS = listOf(
        SpotPrice(Stock(COMPANY_1, DUMMY_OPTION_DATE), Amount(300, OPTION_CURRENCY)),
        SpotPrice(Stock(COMPANY_2, DUMMY_OPTION_DATE), Amount(100_000, OPTION_CURRENCY))
)
val KNOWN_VOLATILITIES = listOf(
        Volatility(Stock(COMPANY_1, DUMMY_OPTION_DATE), 0.40),
        Volatility(Stock(COMPANY_2, DUMMY_OPTION_DATE), 0.05)
)