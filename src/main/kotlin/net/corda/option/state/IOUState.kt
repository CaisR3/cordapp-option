package net.corda.option.state

import net.corda.core.contracts.Amount
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import java.util.*

/**
 * Represents a bilateral IOU on the ledger.
 *
 * @property amount The amount owed by the [borrower] to the [lender].
 * @property lender The party that is owed money.
 * @property borrower The party that owes money.
 * @property paid Records how much of the [amount] has been paid off.
 */
data class IOUState(val amount: Amount<Currency>,
                    val lender: Party,
                    val borrower: Party,
                    val paid: Amount<Currency> = Amount(0, amount.token),
                    override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {

    override val participants get() = listOf(lender, borrower)

    /**
     * Creates a new state with the [paid] amount incremented by [amountToPay].
     * No validation is performed (e.g. checking [paid] doesn't exceed [amount].
     */
    fun pay(amountToPay: Amount<Currency>) = copy(paid = paid + amountToPay)

    /**
     * Creates a copy of the current state with a different lender.
     * Used when transferring the state.
     */
    fun withNewLender(newLender: Party) = copy(lender = newLender)

    override fun toString() = "IOU($linearId): ${borrower.name} owes ${lender.name} $amount and has paid $paid so far."
}