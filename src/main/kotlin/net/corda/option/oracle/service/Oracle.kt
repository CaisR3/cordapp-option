package net.corda.option.oracle.service

import net.corda.core.contracts.Command
import net.corda.core.crypto.TransactionSignature
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import net.corda.finance.DOLLARS
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.AttributeOf
import net.corda.option.datatypes.Spot
import net.corda.option.datatypes.Vol
import org.apache.commons.io.IOUtils
import java.time.Instant

// We sub-class 'SingletonSerializeAsToken' to ensure that instances of this class are never serialised by Kryo.
// When a flow is check-pointed, the annotated @Suspendable methods and any object referenced from within those
// annotated methods are serialised onto the stack. Kryo, the reflection based serialisation framework we use, crawls
// the object graph and serialises anything it encounters, producing a graph of serialised objects.
// This can cause issues. For example, we do not want to serialise large objects on to the stack or objects which may
// reference databases or other external services (which cannot be serialised!). Therefore we mark certain objects with
// tokens. When Kryo encounters one of these tokens, it doesn't serialise the object. Instead, it creates a
// reference to the type of the object. When flows are de-serialised, the token is used to connect up the object
// reference to an instance which should already exist on the stack.
@CordaService
class Oracle(val services: ServiceHub) : SingletonSerializeAsToken() {
    private val myKey = services.myInfo.legalIdentities.first().owningKey

    private val spots = loadSpots()
    private val vols = loadVols()

    // For now, load spots from file and parse into list
    fun loadSpots(): List<Spot> {
        val loadedSpots = IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("oracle/example.spots.txt"), Charsets.UTF_8.name()).lines().filter { it != "" }
        return loadedSpots.map(this::parseSpot).toList()
    }

    // For now, load spots from file and parse into list
    fun loadVols(): List<Vol> {
        val loadedVols = IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("oracle/example.vols.txt"), Charsets.UTF_8.name()).lines().filter { it != "" }
        return loadedVols.map(this::parseVol).toList()
    }

    /** Parses a string of the form "IBM 13:30 = 123" into a [Spot] */
    private fun parseSpot(s: String): Spot {
        try {
            val (key, value) = s.split('=').map(String::trim)
            val of = parseSpotOf(key)
            val price = DOLLARS(value.toDouble())
            return Spot(of, price)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to parse price $s: ${e.message}", e)
        }
    }

    /** Parses a string of the form "IBM 13:30 = 123" into a [Vol] */
    private fun parseVol(s: String): Vol {
        try {
            val (key, value) = s.split('=').map(String::trim)
            val of = parseSpotOf(key)
            val vol = value.toDouble()
            return Vol(of, vol)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to parse vol $s: ${e.message}", e)
        }
    }

    /** Parses a string of the form "IBM 13:30" into a [AttributeOf] */
    fun parseSpotOf(key: String): AttributeOf {
        val words = key.split(' ')
        val time = words.last()
        val name = words.dropLast(1).joinToString(" ")
        return AttributeOf(name, Instant.parse(time))
    }

    // Return spot for a given stock.
    fun querySpot(attributeOf: AttributeOf): Spot {
        return spots.filter { it.of == attributeOf }.single()
    }

    // Return spot for a given stock.
    fun queryVol(attributeOf: AttributeOf): Vol {
        return vols.filter { it.of == attributeOf }.single()
    }

    // Signs over a transaction if the specified Nth prime for a particular N is correct.
    // This function takes a filtered transaction which is a partial Merkle tree. Any parts of the transaction which
    // the oracle doesn't need to see in order to verify the correctness of the nth prime have been removed. In this
    // case, all but the [PrimeContract.Create] commands have been removed. If the Nth prime is correct then the oracle
    // signs over the Merkle root (the hash) of the transaction.
    fun sign(ftx: FilteredTransaction): TransactionSignature {
        // Check the partial Merkle tree is valid.
        ftx.verify()

        val isValid = ftx.checkWithFun {
            when (it) {
                is Command<*> -> {
                    if (it.value is OptionContract.Commands.Exercise) {
                        val cmdData = it.value as OptionContract.Commands.Exercise
                        myKey in it.signers && querySpot(cmdData.spot.of) == cmdData.spot
                    } else {
                        false
                    }
                }

                else -> throw IllegalArgumentException("Oracle received data of a different type than expected.")
            }
        }

        if (isValid) {
            return services.createSignature(ftx, myKey)
        } else {
            throw IllegalArgumentException("Oracle signature requested over invalid transaction.")
        }
    }
}