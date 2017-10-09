package net.corda.option.state

import net.corda.core.contracts.Amount
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.option.OptionType
import java.time.Instant
import java.util.*

data class OptionState(
        val strike: Amount<Currency>,
        val expiry: Instant,
        val underlyingStock: String,
        val currency: Currency,
        val issuer: Party,
        val owner: Party,
        val optionType: OptionType,
        var spotPrice: Amount<Currency> = Amount(0, strike.token),
        val exercised: Boolean = false,
        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {

    override val participants get() = listOf(owner, issuer)

    /**
     * Creates a copy of the current state with a different owner.
     * Used when transferring the state.
     */
    fun withNewOwner(newOwner: Party) = copy(owner = newOwner)

    /**
     * Creates a copy of the current state with exercised set to true and a new spot price.
     */
    fun exercise(newSpotPrice: Amount<Currency>) = copy(exercised = true, spotPrice = newSpotPrice)

    /**
     * Creates a copy of the current state with a different owner.
     * Used when transferring the state.
     */
    infix fun `owned by`(owner: Party) = copy(owner = owner)

    override fun toString() = "${this.optionType.name} option on ${this.underlyingStock} at strike ${this.strike} expiring on ${this.expiry}"
}