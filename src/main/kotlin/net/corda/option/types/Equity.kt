package net.corda.option.datatypes

import net.corda.core.contracts.Amount
import net.corda.core.serialization.CordaSerializable
import java.time.Instant
import java.util.*

@CordaSerializable
data class AttributeOf(val name: String, val forTime: Instant)

/** A [Spot] represents a price for a named stock */
@CordaSerializable
data class Spot(val of: AttributeOf, val value: Amount<Currency>)

/** A [Vol] represents historic volatility for a named stock */
@CordaSerializable
data class Vol(val of: AttributeOf, val value: Double)