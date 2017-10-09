package net.corda.option

import net.corda.core.contracts.Amount
import net.corda.core.serialization.CordaSerializable
import java.time.Instant
import java.util.*

/** Represents a stock at a given point in time. */
@CordaSerializable
data class Stock(val company: String, val atTime: Instant)

/** Represents the price of a given stock at a given point in time. */
@CordaSerializable
data class SpotPrice(val stock: Stock, val value: Amount<Currency>)

/** Represents the historic volatility of a given stock at a given point in time. */
@CordaSerializable
data class Volatility(val stock: Stock, val value: Double)

/** Represents a stock at a given point in time. */
@CordaSerializable
enum class OptionType { CALL, PUT }