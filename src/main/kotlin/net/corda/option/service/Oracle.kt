package net.corda.option.service

import net.corda.core.contracts.Command
import net.corda.core.crypto.TransactionSignature
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import net.corda.finance.DOLLARS
import net.corda.option.*
import net.corda.option.contract.OptionContract
import org.apache.commons.io.IOUtils
import java.time.Instant

/**
 *  We sub-class 'SingletonSerializeAsToken' to ensure that instances of this class are never serialised by Kryo. When
 *  a flow is check-pointed, the annotated @Suspendable methods and any object referenced from within those annotated
 *  methods are serialised onto the stack. Kryo, the reflection based serialisation framework we use, crawls the object
 *  graph and serialises anything it encounters, producing a graph of serialised objects.
 *
 *  This can cause issues. For example, we do not want to serialise large objects on to the stack or objects which may
 *  reference databases or other external services (which cannot be serialised!). Therefore we mark certain objects
 *  with tokens. When Kryo encounters one of these tokens, it doesn't serialise the object. Instead, it creates a
 *  reference to the type of the object. When flows are de-serialised, the token is used to connect up the object
 *  reference to an instance which should already exist on the stack.
 */
@CordaService
class Oracle(val services: ServiceHub) : SingletonSerializeAsToken() {
    private val myKey = services.myInfo.legalIdentities.first().owningKey

    private val knownSpots = loadSpots()
    private val knownVolatilities = loadVolatilities()

    /** Returns spot for a given stock. */
    fun querySpot(stock: Stock): SpotPrice {
        return knownSpots.find { it.stock == stock } ?: throw IllegalArgumentException("Unknown spot.")
    }

    /** Returns volatility for a given stock. */
    fun queryVolatility(stock: Stock): Volatility {
        return knownVolatilities.find { it.stock == stock } ?: throw IllegalArgumentException("Unknown volatility.")
    }

    /** Loads a list of [Spot]s from a file. */
    private fun loadSpots(): List<SpotPrice> {
        val fileNonEmptyLines = loadNonEmptyLinesFromFile(SPOTS_TXT_FILE)
        return fileNonEmptyLines.map { parseSpotFromString(it) }.toList()
    }

    /** Loads a list of [Volatility]s from a file. */
    private fun loadVolatilities(): List<Volatility> {
        val fileNonEmptyLines = loadNonEmptyLinesFromFile(VOLS_TXT_FILE)
        return fileNonEmptyLines.map { parseVolatilityFromString(it) }.toList()
    }

    /** Reads a file into a series of lines, with empty lines filtered out. */
    private fun loadNonEmptyLinesFromFile(fileName: String): List<String> {
        val fileStream = Thread.currentThread().contextClassLoader.getResourceAsStream(fileName)
        val fileString = IOUtils.toString(fileStream, Charsets.UTF_8.name())
        return fileString.lines().filter { it != "" }
    }

    /** Parses a string of the form "IBM 13:30 = 123" into a [Spot]. */
    private fun parseSpotFromString(s: String): SpotPrice {
        val (stock, price) = parseStockAndDoubleFromString(s)
        return SpotPrice(stock, DOLLARS(price))
    }

    /** Parses a string of the form "IBM 13:30 = 123" into a [Volatility]. */
    private fun parseVolatilityFromString(s: String): Volatility {
        val (stock, volatility) = parseStockAndDoubleFromString(s)
        return Volatility(stock, volatility)
    }

    /** Parses a string of the form "IBM 13:30 = 123" into a [Pair] of Stock, Double. */
    private fun parseStockAndDoubleFromString(s: String): Pair<Stock, Double> {
        val (stockNameAndTime, doubleStr) = s.split('=').map { it.trim() }
        val stock = try {
            parseStockFromString(stockNameAndTime)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to parse vol $s: ${e.message}", e)
        }
        val double = doubleStr.toDouble()
        return Pair(stock, double)
    }

    /** Parses a string of the form "IBM 13:30" into a [Stock]. */
    private fun parseStockFromString(key: String): Stock {
        val words = key.split(' ')
        val time = words.last()
        val name = words.dropLast(1).joinToString(" ")
        return Stock(name, Instant.parse(time))
    }

    /**
     * Signs over a transaction if the specified Nth prime for a particular N is correct.
     * This function takes a filtered transaction which is a partial Merkle tree. Any parts of the transaction which
     * the oracle doesn't need to see in order to verify the correctness of the nth prime have been removed. In this
     * case, all but the [PrimeContract.Create] commands have been removed. If the Nth prime is correct then the oracle
     * signs over the Merkle root (the hash) of the transaction.
     */
    fun sign(ftx: FilteredTransaction): TransactionSignature {
        // Is the partial Merkle tree valid?
        ftx.verify()

        /** Returns true if the component is an exercise command that:
         *  - States the correct price
         *  - Has the oracle listed as a signer
         */
        fun isExerciseCommandWithCorrectPriceAndIAmSigner(elem: Any): Boolean {
            return when (elem) {
                is Command<*> -> {
                    if (elem.value is OptionContract.Commands.Exercise) {
                        val cmdData = elem.value as OptionContract.Commands.Exercise
                        myKey in elem.signers && querySpot(cmdData.spot.stock) == cmdData.spot
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        // Is it a Merkle tree we are willing to sign over?
        val isValidMerkleTree = ftx.checkWithFun(::isExerciseCommandWithCorrectPriceAndIAmSigner)

        if (isValidMerkleTree) {
            return services.createSignature(ftx, myKey)
        } else {
            throw IllegalArgumentException("Oracle signature requested over invalid transaction.")
        }
    }
}