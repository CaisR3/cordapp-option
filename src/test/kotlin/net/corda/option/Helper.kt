package net.corda.option

import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.days
import net.corda.option.state.OptionState
import net.corda.option.types.OptionType
import net.corda.testing.node.MockNetwork
import java.time.Instant
import java.util.*

public fun getOption(a: MockNetwork.MockNode, b: MockNetwork.MockNode): OptionState = OptionState(
        strike = 10.DOLLARS,
        expiry = Instant.now() + 30.days,
        currency = Currency.getInstance("USD"),
        underlying = "IBM",
        optionType = OptionType.PUT,
        issuer = a.info.legalIdentity,
        owner = b.info.legalIdentity,
        linearId = UniqueIdentifier.fromString("3a3be8e0-996f-4a9a-a654-e9560df52f14")
)

public fun getBadOption(a: MockNetwork.MockNode, b: MockNetwork.MockNode) : OptionState = OptionState(
        //strike cannot be zero
        strike = 0.DOLLARS,
        expiry = Instant.now() + 30.days,
        currency = Currency.getInstance("USD"),
        underlying = "IBM",
        optionType = OptionType.PUT,
        issuer = a.info.legalIdentity,
        owner = b.info.legalIdentity,
        linearId = UniqueIdentifier.fromString("3a3be8e0-996f-4a9a-a654-e9560df52f14")
)