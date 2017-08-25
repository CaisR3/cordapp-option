package net.corda.examples.oracle


import net.corda.core.contracts.Command
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.TransactionType
import net.corda.core.days
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.*
import net.corda.option.types.OptionType
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.Spot
import net.corda.option.datatypes.AttributeOf
import net.corda.testing.MEGA_CORP
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDataSourceProperties
import net.corda.option.oracle.service.Oracle
import net.corda.option.state.OptionState
import org.apache.commons.io.IOUtils
import org.jetbrains.exposed.sql.Database
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OracleServiceTests {
    val dummyServices = MockServices(CHARLIE_KEY)
    lateinit var oracle: Oracle
    lateinit var dataSource: Closeable
    lateinit var database: Database
    lateinit var attributeOf: AttributeOf

    @Before
    fun setUp() {
        // Mock components for testing the Oracle.
        val dataSourceAndDatabase = configureDatabase(makeTestDataSourceProperties())
        dataSource = dataSourceAndDatabase.first
        database = dataSourceAndDatabase.second
        database.transaction {
            oracle = Oracle(CHARLIE, dummyServices)
        }
        attributeOf = AttributeOf("IBM", Instant.parse("2017-07-03T10:15:30.00Z"))
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `successful query`() {
        database.transaction {
            val result = oracle.querySpot(attributeOf)
            assertEquals(3.DOLLARS, result.value)
        }
    }

    @Test
    fun `unsuccessful query`() {
        database.transaction {
            val result = oracle.querySpot(attributeOf)
            assertNotEquals(5.DOLLARS, result.value)
        }
    }

    @Test
    fun `successful sign`() {
        database.transaction {
            val spot = Spot(attributeOf, 3.DOLLARS)
            val command = Command(OptionContract.Commands.Exercise(spot), listOf(CHARLIE.owningKey))
            val state = getOption()
            val wtx: WireTransaction = TransactionType.General.Builder(DUMMY_NOTARY)
                    .withItems(state, command)
                    .toWireTransaction()
            val ftx: FilteredTransaction = wtx.buildFilteredTransaction ({
                when (it) {
                    is Command -> oracle.identity.owningKey in it.signers && it.value is OptionContract.Commands.Exercise
                    else -> false
                }
            })
            val signature = oracle.sign(ftx)
            assert(signature.verify(ftx.rootHash.bytes))
        }
    }

    @Test
    fun `incorrect spot specified`() {
        database.transaction {
            val spot = Spot(attributeOf, 20.DOLLARS)
            val command = Command(OptionContract.Commands.Exercise(spot), listOf(CHARLIE.owningKey))
            val state = getOption()
            val wtx: WireTransaction = TransactionType.General.Builder(DUMMY_NOTARY).withItems(state, command).toWireTransaction()
            val ftx: FilteredTransaction = wtx.buildFilteredTransaction ({
                when (it) {
                    is Command -> oracle.identity.owningKey in it.signers && it.value is OptionContract.Commands.Exercise
                    else -> false
                }
            })
            assertFailsWith<IllegalArgumentException> { oracle.sign(ftx) }
        }
    }

    fun getOption() : OptionState = OptionState(
            strike = 10.DOLLARS,
            expiry = TEST_TX_TIME + 30.days,
            currency = Currency.getInstance("USD"),
            underlying = "IBM",
            issuer = MEGA_CORP,
            owner = MEGA_CORP,
            optionType = OptionType.PUT
    )
}
