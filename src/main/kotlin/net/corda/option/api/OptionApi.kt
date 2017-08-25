package net.corda.option.api

import net.corda.client.rpc.notUsed
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.days
import net.corda.core.getOrThrow
import net.corda.core.messaging.CordaRPCOps
import net.corda.option.flow.*
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import net.corda.option.types.OptionType
import org.bouncycastle.asn1.x500.X500Name
import rx.Observable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

val SERVICE_NODE_NAMES = listOf(
        X500Name("CN=Controller,O=R3,L=London,C=UK"),
        X500Name("CN=NetworkMapService,O=R3,L=London,C=UK"))

/**
 * This API is accessible from /api/option. The endpoint paths specified below are relative to it.
 * We've defined a bunch of endpoints to deal with options, IOUs, cash and the various operations you can perform with them.
 */
@Path("option")
class OptionApi(val services: CordaRPCOps) {
    private val myLegalName = services.nodeIdentity().legalIdentity.name

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun whoami() = mapOf("me" to myLegalName)

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun getPeers(): Map<String, List<X500Name>> {
        val (nodeInfo, nodeUpdates) = services.networkMapUpdates()
        nodeUpdates.notUsed()
        return mapOf("peers" to nodeInfo
                .map { it.legalIdentity.name }
                .filter { it != myLegalName && it !in SERVICE_NODE_NAMES })
    }

    /**
     * Displays all Option states that exist in the node's vault.
     */
    @GET
    @Path("options")
    @Produces(MediaType.APPLICATION_JSON)
            // Filter by state type: Option.
    fun getOptions(): List<StateAndRef<ContractState>> {
        val result = services.vaultAndUpdates().justSnapshot.filter { it.state.data is OptionState }
        return result
    }

    /**
     * Displays all IOU states that exist in the node's vault.
     */
    @GET
    @Path("ious")
    @Produces(MediaType.APPLICATION_JSON)
            // Filter by state type: Option.
    fun getIOUs(): List<StateAndRef<ContractState>> {
        val result = services.vaultAndUpdates().justSnapshot.filter { it.state.data is IOUState }
        return result
    }

    /**
     * Displays all cash states that exist in the node's vault.
     */
    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
            // Filter by state type: Cash.
    fun getCash(): List<StateAndRef<ContractState>> {
        return services.vaultAndUpdates().justSnapshot.filter { it.state.data is Cash.State }
    }

    /**
     * Displays all cash states that exist in the node's vault.
     */
    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
            // Display cash balances.
    fun getCashBalances(): Map<Currency, Amount<Currency>> = services.getCashBalances()

    /**
     * Initiates a flow to agree an Option between two parties.
     */
    @GET
    @Path("issue-option")
    fun issueOption(@QueryParam(value = "strike") strike: Int,
                    @QueryParam(value = "currency") currency: String,
                    @QueryParam(value = "expiry") expiry: String,
                    @QueryParam(value = "underlying") underlying: String,
                    @QueryParam(value = "counterparty") counterparty: String,
                    @QueryParam(value = "optionType") optionType: String): Response {
        // Get party objects for myself and the counterparty.
        val me = services.nodeIdentity().legalIdentity
        val party = services.partyFromX500Name(X500Name(counterparty)) ?: throw IllegalArgumentException("Unknown party name.")
        val expiryInstant = LocalDate.parse(expiry).atStartOfDay().toInstant(ZoneOffset.UTC)
        val optType = if (optionType.equals("CALL")) OptionType.CALL else OptionType.PUT
        // Create a new Option state using the parameters given.
        val state = OptionState(Amount(strike.toLong() * 100, Currency.getInstance(currency)), expiryInstant, underlying, Currency.getInstance(currency), me, party, optType)

        // Start the OptionIssueFlow. We block and wait for the flow to return.
        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(OptionIssueFlow.Initiator::class.java, state)
            val result = flowHandle.use { it.returnValue.getOrThrow() }
            // Return the response.
            Response.Status.CREATED to "Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.single()}"
        } catch (e: Exception) {
            // For the purposes of this demo app, we do not differentiate by exception type.
            Response.Status.BAD_REQUEST to e.message
        }

        return Response.status(status).entity(message).build()
    }

    /**
     * Initiates a flow to agree an Option between two parties.
     */
    @GET
    @Path("request-option")
    fun requestOption(@QueryParam(value = "strike") strike: Int,
                    @QueryParam(value = "currency") currency: String,
                    @QueryParam(value = "expiry") expiry: String,
                    @QueryParam(value = "underlying") underlying: String,
                    @QueryParam(value = "issuer") issuer: String,
                    @QueryParam(value = "optionType") optionType: String): Response {
        // Get party objects for myself and the issuer.
        val me = services.nodeIdentity().legalIdentity
        val party = services.partyFromX500Name(X500Name(issuer)) ?: throw IllegalArgumentException("Unknown party name.")
        val expiryInstant = LocalDate.parse(expiry).atStartOfDay().toInstant(ZoneOffset.UTC)
        val optType = if (optionType.equals("CALL")) OptionType.CALL else OptionType.PUT
        // Create a new Option state using the parameters given.
        val state = OptionState(Amount(strike.toLong() * 100, Currency.getInstance(currency)), expiryInstant, underlying, Currency.getInstance(currency), party, me, optType)

        // Start the OptionIssueFlow. We block and wait for the flow to return.
        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(OptionRequestFlow.Initiator::class.java, state)
            val result = flowHandle.use { it.returnValue.getOrThrow() }
            // Return the response.
            Response.Status.CREATED to "Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.first()} and \n${result.tx.outputs.last()}"
        } catch (e: Exception) {
            // For the purposes of this demo app, we do not differentiate by exception type.
            Response.Status.BAD_REQUEST to e.message
        }

        return Response.status(status).entity(message).build()
    }

    /**
     * Initiates a flow to agree a default option between two parties (for testing)
     */
    @GET
    @Path("issue-test-option")
    fun defaultIssueOption(): Response {
        val amount = 10
        val currency = "USD"
        val expiry = Instant.now() + 30.days
        val underlying = "IBM"
        val counterparty = "CN=NodeB,O=NodeB,L=New York,C=US"
        val optionType = OptionType.PUT

        // Get party objects for myself and the counterparty.
        val me = services.nodeIdentity().legalIdentity
        val party = services.partyFromX500Name(X500Name(counterparty)) ?: throw IllegalArgumentException("Unknown party name.")
        // Create a new Option state using the parameters given.
        val state = OptionState(Amount(amount.toLong() * 100, Currency.getInstance(currency)), expiry, underlying, Currency.getInstance(currency), me, party, optionType)
        // Start the OptionIssueFlow. We block and waits for the flow to return.
        try {
            val result = services.startFlowDynamic(OptionIssueFlow.Initiator::class.java, state).returnValue.get()
            // Return the response.
            return Response
                    .status(Response.Status.CREATED)
                    .entity("Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.single()}")
                    .build()
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (e: Exception) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.message)
                    .build()
        }
    }

    /**
     * tranfers an Option specified by transaction id to a new party.
     */
    @GET
    @Path("trade-option")
    fun tradeOption(@QueryParam(value = "id") id: String,
                    @QueryParam(value = "party") counterparty: String): Response {
        val linearId = UniqueIdentifier.fromString(id)
        val party = services.partyFromX500Name(X500Name(counterparty)) ?: throw IllegalArgumentException("Unknown party name.")

        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(OptionTradeFlow.Initiator::class.java, linearId, party)
            // We don't care about the signed tx returned by the flow, only that it finishes successfully
            flowHandle.use { flowHandle.returnValue.getOrThrow() }
            Response.Status.CREATED to "Option $id transferred to $party."
        } catch (e: Exception) {
            Response.Status.BAD_REQUEST to e.message
        }

        return Response.status(status).entity(message).build()
    }

    /**
     * Settles an Option. Requires cash in the right currency to be able to settle.
     */
    @GET
    @Path("exercise-option")
    fun exerciseOption(@QueryParam(value = "id") id: String): Response {
        val linearId = UniqueIdentifier.fromString(id)

        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(OptionExerciseFlow.Initiator::class.java, linearId)
            flowHandle.use { flowHandle.returnValue.getOrThrow() }
            Response.Status.CREATED to "option exercised."
        } catch (e: Exception) {
            Response.Status.BAD_REQUEST to e.message
        }

        return Response.status(status).entity(message).build()
    }

    /**
     * Helper end-point to issue some cash to ourselves.
     */
    @GET
    @Path("self-issue-cash")
    fun selfIssueCash(@QueryParam(value = "amount") amount: Int,
                      @QueryParam(value = "currency") currency: String): Response {
        val issueAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))

        val (status, message) = try {
            val flowHandle = services.startTrackedFlowDynamic(SelfIssueCashFlow::class.java, issueAmount)
            val cashState = flowHandle.use { it.returnValue.getOrThrow() }
            Response.Status.CREATED to cashState.toString()
        } catch (e: Exception) {
            Response.Status.BAD_REQUEST to e.message
        }

        return Response.status(status).entity(message).build()
    }

    // Helper method to get just the snapshot portion of an RPC call which also returns an Observable of updates. It's
    // important to unsubscribe from this Observable if we're not going to use it as otherwise we leak resources on the server.
    private val <A> Pair<A, Observable<*>>.justSnapshot: A get() {
        second.notUsed()
        return first
    }
}