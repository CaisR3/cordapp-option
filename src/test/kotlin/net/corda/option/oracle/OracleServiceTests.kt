package net.corda.option.oracle

import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.days
import net.corda.finance.DOLLARS
import net.corda.option.*
import net.corda.option.contract.OptionContract
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.service.Oracle
import net.corda.option.state.OptionState
import net.corda.testing.*
import net.corda.testing.node.MockServices
import org.junit.Test
import java.util.function.Predicate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OracleServiceTests : TestDependencyInjectionBase() {
    private val dummyServices = MockServices(listOf("net.corda.option.contract"), CHARLIE_KEY)
    private val oracle = Oracle(dummyServices)
    private val stock = Stock(COMPANY_1, DUMMY_OPTION_DATE)

    @Test
    fun `successful query`() {
        val result = oracle.querySpot(stock)
        assertEquals(3.DOLLARS, result.value)
    }

    @Test
    fun `unsuccessful query`() {
        val result = oracle.querySpot(stock)
        assertNotEquals(5.DOLLARS, result.value)

    }

    @Test
    fun `successful sign`() {
        val spot = SpotPrice(stock, 3.DOLLARS)
        val command = Command(OptionContract.Commands.Redeem(spot), listOf(CHARLIE.owningKey))
        val state = getOption()
        val stateAndContract = StateAndContract(state, OPTION_CONTRACT_ID)
        val ftx = TransactionBuilder(DUMMY_NOTARY)
                .withItems(stateAndContract, command)
                .toWireTransaction(dummyServices)
                .buildFilteredTransaction(Predicate {
                    when (it) {
                        is Command<*> ->
                            oracle.services.myInfo.legalIdentities.first().owningKey in it.signers
                                    && it.value is OptionContract.Commands.Redeem

                        else -> false
                    }
                })
        val signature = oracle.sign(ftx)
        assert(signature.verify(ftx.id))
    }

    @Test
    fun `incorrect spot price specified`() {
        val spot = SpotPrice(stock, 20.DOLLARS)
        val command = Command(OptionContract.Commands.Redeem(spot), listOf(CHARLIE.owningKey))
        val state = getOption()
        val stateAndContract = StateAndContract(state, OPTION_CONTRACT_ID)
        val ftx = TransactionBuilder(DUMMY_NOTARY)
                .withItems(stateAndContract, command)
                .toWireTransaction(dummyServices)
                .buildFilteredTransaction(Predicate {
                    when (it) {
                        is Command<*> -> oracle.services.myInfo.legalIdentities.first().owningKey in it.signers && it.value is OptionContract.Commands.Exercise
                        else -> false
                    }
                })
        assertFailsWith<IllegalArgumentException> { oracle.sign(ftx) }

    }

    private fun getOption(): OptionState = OptionState(
            strikePrice = 10.DOLLARS,
            expiryDate = TEST_TX_TIME + 30.days,
            underlyingStock = "IBM",
            issuer = MEGA_CORP,
            owner = MEGA_CORP,
            optionType = OptionType.PUT
    )
}
