package net.corda.option

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.core.utilities.days
import net.corda.finance.DOLLARS
import net.corda.node.internal.StartedNode
import net.corda.option.state.OptionState
import net.corda.testing.MEGA_CORP
import net.corda.testing.TEST_TX_TIME
import net.corda.testing.node.MockNetwork.MockNode
import java.time.Instant
import java.util.*

val OPTION_LINEAR_ID = UniqueIdentifier.fromString("3a3be8e0-996f-4a9a-a654-e9560df52f14")

fun createOption(issuer: Party, owner: Party): OptionState = OptionState(
        strike = 10.DOLLARS,
        expiry = Instant.now() + 30.days,
        currency = Currency.getInstance("USD"),
        underlyingStock = "IBM",
        optionType = OptionType.PUT,
        issuer = issuer,
        owner = owner,
        linearId = OPTION_LINEAR_ID
)

fun createBadOption(issuer: Party, owner: Party) : OptionState = OptionState(
        // An option with a strike price of zero is invalid.
        strike = 0.DOLLARS,
        expiry = Instant.now() + 30.days,
        currency = Currency.getInstance("USD"),
        underlyingStock = "IBM",
        optionType = OptionType.PUT,
        issuer = issuer,
        owner = owner,
        linearId = OPTION_LINEAR_ID
)