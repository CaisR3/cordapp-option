package net.corda.option.state

import net.corda.core.contracts.Amount
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.util.*

/**
 * The IOU State object, with the following properties:
 * - [amount] The amount owed by the [borrower] to the [lender]
 * - [lender] The lending party.
 * - [borrower] The borrowing party.
 * - [paid] Records how much of the [amount] has been paid.
 * - [linearId] A unique id shared by all LinearState states representing the same agreement throughout history within
 *   the vaults of all parties. Verify methods should check that one input and one output share the id in a transaction,
 *   except at issuance/termination.
 */
data class IOUState(val amount: Amount<Currency>,
                    val lender: Party,
                    val borrower: Party,
                    val paid: Amount<Currency> = Amount(0, amount.token),
                    override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState {

    /**
     *  This property holds a list of the nodes which can "use" this state in a valid transaction. In this case, the
     *  lender or the borrower.
     */
    override val participants: List<AbstractParty> get() = listOf(lender, borrower)

    /**
     * Helper method which creates a new state with the [paid] amount incremented by [amountToPay]. No validation is performed.
     */
    fun pay(amountToPay: Amount<Currency>) = copy(paid = paid + amountToPay)

    /**
     * Helper method which creates a copy of the current state with a newly specified lender. For use when transferring.
     */
    fun withNewLender(newLender: Party) = copy(lender = newLender)

    /**
     * A toString() helper method for displaying IOUs in the console.
     */
    override fun toString() = "IOU($linearId): ${borrower.name} owes ${lender.name} $amount and has paid $paid so far."
}