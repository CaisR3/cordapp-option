package net.corda.option

import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.finance.USD
import java.time.Instant

val ORACLE_NAME = CordaX500Name("PartyD", "New York","US")
val DUMMY_CURRENT_DATE = Instant.parse("2017-07-03T10:15:30.00Z")!!
val OPTION_CURRENCY = USD
val RISK_FREE_RATE = 0.01

val COMPANY_STOCK_1 = "IBM"
val COMPANY_STOCK_2 = "R3"
val COMPANY_AMOUNT_1 = Amount(300, OPTION_CURRENCY)
val COMPANY_AMOUNT_2 = Amount(100_000, OPTION_CURRENCY)
val COMPANY_VOLATILITY_1 = 0.40
val COMPANY_VOLATILITY_2 = 0.05

val KNOWN_SPOTS = listOf(
        SpotPrice(COMPANY_STOCK_1, DUMMY_CURRENT_DATE, COMPANY_AMOUNT_1),
        SpotPrice(COMPANY_STOCK_2, DUMMY_CURRENT_DATE, COMPANY_AMOUNT_2)
)
val KNOWN_VOLATILITIES = listOf(
        Volatility(COMPANY_STOCK_1, DUMMY_CURRENT_DATE, COMPANY_VOLATILITY_1),
        Volatility(COMPANY_STOCK_2, DUMMY_CURRENT_DATE, COMPANY_VOLATILITY_2)
)