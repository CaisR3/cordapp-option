package net.corda.option.state

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.option.contract.OptionContract
import net.corda.option.types.OptionType
import java.time.Instant
import java.util.*

data class OptionState(
        val strike: Amount<Currency>,
        val expiry: Instant,
        val underlying: String,
        val currency: Currency,
        val issuer: Party,
        override val owner: Party,
        val optionType: OptionType,
        var spot: Amount<Currency> = Amount(0, strike.token),
        val exercised: Boolean = false,
        override val linearId: UniqueIdentifier = UniqueIdentifier()) : OwnableState, LinearState {

    /** The public keys of the involved parties. */
    override val participants: List<AbstractParty> get() = listOf(owner, issuer)

    override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(OptionContract.Commands.Trade(), copy(owner = newOwner as Party))

    fun exercise(newSpot: Amount<Currency>) = copy(exercised = true, spot = newSpot)

    infix fun `owned by`(owner: Party) = copy(owner = owner)

    override fun toString() = "${this.optionType.name} option on ${this.underlying} at strike ${this.strike} expiring on ${this.expiry}"
}