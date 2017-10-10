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

    private val option = OptionState(
            strikePrice = 10.DOLLARS,
            expiryDate = TEST_TX_TIME + 30.days,
            underlyingStock = COMPANY_STOCK_1.company,
            issuer = MEGA_CORP,
            owner = MEGA_CORP,
            optionType = OptionType.PUT
    )

    @Test
    fun `successful query`() {
        val result = oracle.querySpot(COMPANY_STOCK_1)
        assertEquals(3.DOLLARS, result.value)
    }

    @Test
    fun `successful sign`() {
        val command = Command(OptionContract.Commands.Issue(KNOWN_SPOTS[0], KNOWN_VOLATILITIES[0]), listOf(CHARLIE.owningKey))
        val stateAndContract = StateAndContract(option, OPTION_CONTRACT_ID)
        val ftx = TransactionBuilder(DUMMY_NOTARY)
                .withItems(stateAndContract, command)
                .toWireTransaction(dummyServices)
                .buildFilteredTransaction(Predicate {
                    when (it) {
                        is Command<*> ->
                            oracle.services.myInfo.legalIdentities.first().owningKey in it.signers
                                    && it.value is OptionContract.Commands.Issue

                        else -> false
                    }
                })
        val signature = oracle.sign(ftx)
        assert(signature.verify(ftx.id))
    }

    @Test
    fun `incorrect spot price specified`() {
        val incorrectSpot = SpotPrice(COMPANY_STOCK_1, 20.DOLLARS)
        val command = Command(OptionContract.Commands.Issue(incorrectSpot, KNOWN_VOLATILITIES[0]), listOf(CHARLIE.owningKey))
        val stateAndContract = StateAndContract(option, OPTION_CONTRACT_ID)
        val ftx = TransactionBuilder(DUMMY_NOTARY)
                .withItems(stateAndContract, command)
                .toWireTransaction(dummyServices)
                .buildFilteredTransaction(Predicate {
                    when (it) {
                        is Command<*> -> oracle.services.myInfo.legalIdentities.first().owningKey in it.signers
                                && it.value is OptionContract.Commands.Issue
                        else -> false
                    }
                })
        assertFailsWith<IllegalArgumentException> { oracle.sign(ftx) }
    }

    @Test
    fun `incorrect volatility specified`() {
        val incorrectVolatility = Volatility(COMPANY_STOCK_1, 1.toDouble())
        val command = Command(OptionContract.Commands.Issue(KNOWN_SPOTS[0], incorrectVolatility), listOf(CHARLIE.owningKey))
        val stateAndContract = StateAndContract(option, OPTION_CONTRACT_ID)
        val ftx = TransactionBuilder(DUMMY_NOTARY)
                .withItems(stateAndContract, command)
                .toWireTransaction(dummyServices)
                .buildFilteredTransaction(Predicate {
                    when (it) {
                        is Command<*> -> oracle.services.myInfo.legalIdentities.first().owningKey in it.signers
                                && it.value is OptionContract.Commands.Issue
                        else -> false
                    }
                })
        assertFailsWith<IllegalArgumentException> { oracle.sign(ftx) }
    }
}
