package net.corda.option.oracle

import net.corda.core.contracts.Command
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.days
import net.corda.finance.DOLLARS
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.AttributeOf
import net.corda.option.datatypes.Spot
import net.corda.option.oracle.service.Oracle
import net.corda.option.state.OptionState
import net.corda.option.types.OptionType
import net.corda.testing.*
import net.corda.testing.node.MockServices
import org.junit.Test
import java.time.Instant
import java.util.*
import java.util.function.Predicate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OracleServiceTests {
    private val dummyServices = MockServices(listOf("net.corda.examples.oracle.base.contract"), CHARLIE_KEY)
    private val oracle = Oracle(dummyServices)
    private val attributeOf = AttributeOf("IBM", Instant.parse("2017-07-03T10:15:30.00Z"))

    @Test
    fun `successful query`() {
        val result = oracle.querySpot(attributeOf)
        assertEquals(3.DOLLARS, result.value)
    }

    @Test
    fun `unsuccessful query`() {
        val result = oracle.querySpot(attributeOf)
        assertNotEquals(5.DOLLARS, result.value)

    }

    @Test
    fun `successful sign`() {
        val spot = Spot(attributeOf, 3.DOLLARS)
        val command = Command(OptionContract.Commands.Exercise(spot), listOf(CHARLIE.owningKey))
        val state = getOption()
        val ftx = TransactionBuilder(DUMMY_NOTARY)
                .withItems(state, command)
                .toWireTransaction(dummyServices)
                .buildFilteredTransaction(Predicate {
                    when (it) {
                        is Command<*> -> oracle.services.myInfo.legalIdentities.first().owningKey in it.signers && it.value is OptionContract.Commands.Exercise
                        else -> false
                    }
                })
        val signature = oracle.sign(ftx)
        assert(signature.verify(ftx.id))
    }

    @Test
    fun `incorrect spot specified`() {
        val spot = Spot(attributeOf, 20.DOLLARS)
        val command = Command(OptionContract.Commands.Exercise(spot), listOf(CHARLIE.owningKey))
        val state = getOption()
        val ftx = TransactionBuilder(DUMMY_NOTARY)
                .withItems(state, command)
                .toWireTransaction(dummyServices)
                .buildFilteredTransaction(Predicate {
                    when (it) {
                        is Command<*> -> oracle.services.myInfo.legalIdentities.first().owningKey in it.signers && it.value is OptionContract.Commands.Exercise
                        else -> false
                    }
                })
        assertFailsWith<IllegalArgumentException> { oracle.sign(ftx) }

    }

    fun getOption(): OptionState = OptionState(
            strike = 10.DOLLARS,
            expiry = TEST_TX_TIME + 30.days,
            currency = Currency.getInstance("USD"),
            underlying = "IBM",
            issuer = MEGA_CORP,
            owner = MEGA_CORP,
            optionType = OptionType.PUT
    )
}
