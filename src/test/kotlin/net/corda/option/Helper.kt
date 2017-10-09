package net.corda.option

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.core.utilities.days
import net.corda.finance.DOLLARS
import net.corda.node.internal.StartedNode
import net.corda.option.state.OptionState
import net.corda.testing.node.MockNetwork.MockNode
import java.time.Instant
import java.util.*

fun getOption(issuer: Party, owner: Party): OptionState = OptionState(
        strike = 10.DOLLARS,
        expiry = Instant.now() + 30.days,
        currency = Currency.getInstance("USD"),
        underlyingStock = "IBM",
        optionType = OptionType.PUT,
        issuer = issuer,
        owner = owner,
        linearId = UniqueIdentifier.fromString("3a3be8e0-996f-4a9a-a654-e9560df52f14")
)

fun getOption(issuer: StartedNode<MockNode>, owner: StartedNode<MockNode>): OptionState =
        getOption(issuer.info.legalIdentities.first(), owner.info.legalIdentities.first())


fun getBadOption(a: StartedNode<MockNode>, b: StartedNode<MockNode>) : OptionState = OptionState(
        //strike cannot be zero
        strike = 0.DOLLARS,
        expiry = Instant.now() + 30.days,
        currency = Currency.getInstance("USD"),
        underlyingStock = "IBM",
        optionType = OptionType.PUT,
        issuer = a.info.legalIdentities.first(),
        owner = b.info.legalIdentities.first(),
        linearId = UniqueIdentifier.fromString("3a3be8e0-996f-4a9a-a654-e9560df52f14")
)