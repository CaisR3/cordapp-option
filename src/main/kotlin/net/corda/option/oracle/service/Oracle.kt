package net.corda.option.oracle.service

import net.corda.core.contracts.Command
import net.corda.core.contracts.DOLLARS
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.MerkleTreeException
import net.corda.core.identity.Party
import net.corda.core.node.PluginServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.node.services.ServiceType
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.Spot
import net.corda.option.datatypes.AttributeOf
import net.corda.option.datatypes.Vol
import java.io.File
import java.time.Instant

// We sub-class 'SingletonSerializeAsToken' to ensure that instances of this class are never serialised by Kryo.
// When a flow is check-pointed, the annotated @Suspendable methods and any object referenced from within those
// annotated methods are serialised onto the stack. Kryo, the reflection based serialisation framework we use, crawls
// the object graph and serialises anything it encounters, producing a graph of serialised objects.
// This can cause some issues, for example: we do not want to serialise large objects on to the stack or objects which
// may reference databases or other external services (which cannot be serialised!), therefore we mark certain objects
// with tokens. When Kryo encounters one of these tokens, it doesn't serialise the object, instead, it makes a
// reference to the type of the object. When flows are de-serialised, the token is used to connect up the object reference
// to an instance which should already exist on the stack.
    @CordaService
    class Oracle(val identity: Party, val services: ServiceHub) : SingletonSerializeAsToken() {
        // @CordaService requires us to have a constructor that takes in a single parameter of type PluginServiceHub.
        // This is used by the node to automatically install the Oracle.
        // We use the primary constructor for testing.
        constructor(services: PluginServiceHub) : this(services.myInfo.serviceIdentities(type).first(), services)

        companion object {
            // We need a public static ServiceType field named "type". This will allow the node to check if it's declared
            // in the advertisedServices config and only attempt to load the Oracle if it is.
            @JvmField
            val type = ServiceType.getServiceType("net.corda.option", "stocks_oracle")
        }

        //private val spots: List<Spot> = parseFile(IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("example.spots.txt"), Charsets.UTF_8.name()))
        private val spots: List<Spot> = loadSpots()
        private val vols: List<Vol> = loadVols()

        // For now, load spots from file and parse into list
        fun loadSpots(): List<Spot> {
            val loadedSpots = File("C:\\Dev\\option\\src\\main\\kotlin\\net\\corda\\option\\oracle\\service\\example.spots.txt").readLines()
            return loadedSpots.map(this::parseSpot).toList()
        }

        // For now, load spots from file and parse into list
        fun loadVols(): List<Vol> {
            val loadedVols = File("C:\\Dev\\option\\src\\main\\kotlin\\net\\corda\\option\\oracle\\service\\example.vols.txt").readLines()
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


    // Signs over a transaction if the specified spot for a particular stock is correct.
        // This function takes a filtered transaction which is a partial Merkle tree. Parts of the transaction which
        // the Oracle doesn't need to see to opine over the correctness of the spot have been removed. If the spot is correct then the Oracle signs over
        // the Merkle root (the hash) of the transaction.
        fun sign(ftx: FilteredTransaction): DigitalSignature.LegallyIdentifiable {
            // Check the partial Merkle tree is valid.
            if (!ftx.verify()) throw MerkleTreeException("Couldn't verify partial Merkle tree.")

            fun commandValidator(elem: Command): Boolean {
                // This Oracle only cares about commands which have its public key in the signers list.
                // This Oracle also only cares about OptionContract.Exercise commands.
                // Of course, some of these constraints can be easily amended. E.g. they Oracle can sign over multiple
                // command types.
                if (!(identity.owningKey in elem.signers && elem.value is OptionContract.Commands.Exercise))
                    throw IllegalArgumentException("Oracle received unknown command (not in signers or not Spot).")
                val exerciseCommand = elem.value as OptionContract.Commands.Exercise

                // This is where the check the spot value is correct
                return querySpot(exerciseCommand.spot.of) == exerciseCommand.spot
            }

            // This function is run for each non-hash leaf of the Merkle tree.
            // We only expect to see commands.
            fun check(elem: Any): Boolean {
                return when (elem) {
                    is Command -> commandValidator(elem)
                    else -> throw IllegalArgumentException("Oracle received data of different type than expected.")
                }
            }

            // Validate the commands.
            val leaves = ftx.filteredLeaves
            if (!leaves.checkWithFun(::check)) throw IllegalArgumentException()

            // Sign over the Merkle root and return the digital signature.
            val signature = services.keyManagementService.sign(ftx.rootHash.bytes, identity.owningKey)
            return DigitalSignature.LegallyIdentifiable(identity, signature.bytes)
        }
    }