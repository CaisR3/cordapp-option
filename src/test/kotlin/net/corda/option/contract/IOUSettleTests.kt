package net.corda.option.contract

import net.corda.core.contracts.Amount
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.identity.AbstractParty
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.DOLLARS
import net.corda.finance.POUNDS
import net.corda.finance.`issued by`
import net.corda.finance.contracts.asset.CASH_PROGRAM_ID
import net.corda.finance.contracts.asset.Cash
import net.corda.option.contract.IOUContract.Companion.IOU_CONTRACT_ID
import net.corda.option.state.IOUState
import net.corda.testing.*
import org.junit.Test
import java.util.*

class IOUSettleTests {
    val defaultRef = OpaqueBytes(ByteArray(1, { 1 }))
    val defaultIssuer = MEGA_CORP.ref(defaultRef)

    private fun createCashState(amount: Amount<Currency>, owner: AbstractParty): Cash.State {
        return Cash.State(amount = amount `issued by` defaultIssuer, owner = owner)
    }

    // A pre-defined dummy command.
    class DummyCommand : TypeOnlyCommandData()

    @Test
    fun mustIncludeSettleCommand() {
        val iou = IOUState(10.POUNDS, ALICE, BOB)
        val inputCash = createCashState(5.POUNDS, BOB)
        val outputCash = inputCash.withNewOwner(newOwner = ALICE).ownableState
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.pay(5.POUNDS) }
                input(CASH_PROGRAM_ID) { inputCash }
                output(CASH_PROGRAM_ID) { outputCash }
                command(BOB.owningKey) { Cash.Commands.Move() }
                this.fails()
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.pay(5.POUNDS) }
                input(CASH_PROGRAM_ID) { inputCash }
                output(CASH_PROGRAM_ID) { outputCash }
                command(BOB.owningKey) { Cash.Commands.Move() }
                command(ALICE.owningKey, BOB.owningKey) { DummyCommand() } // Wrong type.
                this.fails()
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.pay(5.POUNDS) }
                input(CASH_PROGRAM_ID) { inputCash }
                output(CASH_PROGRAM_ID) { outputCash }
                command(BOB.owningKey) { Cash.Commands.Move() }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() } // Correct Type.
                this.verifies()
            }
        }
    }

    @Test
    fun mustBeOneGroupOfIOUs() {
        val iouOne = IOUState(10.POUNDS, ALICE, BOB)
        val iouTwo = IOUState(5.POUNDS, ALICE, BOB)
        val inputCash = createCashState(5.POUNDS, BOB)
        val outputCash = inputCash.withNewOwner(newOwner = ALICE).ownableState
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iouOne }
                input(IOU_CONTRACT_ID) { iouTwo }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                output(IOU_CONTRACT_ID) { iouOne.pay(5.POUNDS) }
                input(CASH_PROGRAM_ID) { inputCash }
                output(CASH_PROGRAM_ID) { outputCash }
                command(BOB.owningKey) { Cash.Commands.Move() }
                this `fails with` "List has more than one element."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iouOne }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                output(IOU_CONTRACT_ID) { iouOne.pay(5.POUNDS) }
                input(CASH_PROGRAM_ID) { inputCash }
                output(CASH_PROGRAM_ID) { outputCash }
                command(BOB.owningKey) { Cash.Commands.Move() }
                this.verifies()
            }
        }
    }

    @Test
    fun mustHaveOneInputIOU() {
        val iou = IOUState(1.POUNDS, ALICE, BOB)
        val iouOne = IOUState(10.POUNDS, ALICE, BOB)
        val tenPounds = createCashState(10.POUNDS, BOB)
        val fivePounds = createCashState(5.POUNDS, BOB)
        ledger {
            transaction {
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                output(IOU_CONTRACT_ID) { iou }
                this `fails with` "There must be one input IOU."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iouOne }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                output(IOU_CONTRACT_ID) { iouOne.pay(5.POUNDS) }
                input(CASH_PROGRAM_ID) { fivePounds }
                output(CASH_PROGRAM_ID) { fivePounds.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { Cash.Commands.Move() }
                this.verifies()
            }
            transaction {
                input(IOU_CONTRACT_ID) { iouOne }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                input(CASH_PROGRAM_ID) { tenPounds }
                output(CASH_PROGRAM_ID) { tenPounds.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { Cash.Commands.Move() }
                this.verifies()
            }
        }
    }

    @Test
    fun mustBeCashOutputStatesPresent() {
        val iou = IOUState(10.DOLLARS, ALICE, BOB)
        val cash = createCashState(5.DOLLARS, BOB)
        val cashPayment = cash.withNewOwner(newOwner = ALICE)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "There must be output cash."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { cash }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                output(CASH_PROGRAM_ID) { cashPayment.ownableState }
                command(BOB.owningKey) { cashPayment.command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this.verifies()
            }
        }
    }

    @Test
    fun mustBeCashOutputStatesWithRecipientAsOwner() {
        val iou = IOUState(10.POUNDS, ALICE, BOB)
        val cash = createCashState(5.POUNDS, BOB)
        val invalidCashPayment = cash.withNewOwner(newOwner = CHARLIE)
        val validCashPayment = cash.withNewOwner(newOwner = ALICE)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { cash }
                output(IOU_CONTRACT_ID) { iou.pay(5.POUNDS) }
                output(CASH_PROGRAM_ID) { invalidCashPayment.ownableState }
                command(BOB.owningKey) { invalidCashPayment.command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "There must be output cash paid to the recipient."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { cash }
                output(IOU_CONTRACT_ID) { iou.pay(5.POUNDS) }
                output(CASH_PROGRAM_ID) { validCashPayment.ownableState }
                command(BOB.owningKey) { validCashPayment.command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this.verifies()
            }
        }
    }

    @Test
    fun cashSettlementAmountMustBeLessThanRemainingIOUAmount() {
        val iou = IOUState(10.DOLLARS, ALICE, BOB)
        val elevenDollars = createCashState(11.DOLLARS, BOB)
        val tenDollars = createCashState(10.DOLLARS, BOB)
        val fiveDollars = createCashState(5.DOLLARS, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { elevenDollars }
                output(IOU_CONTRACT_ID) { iou.pay(11.DOLLARS) }
                output(CASH_PROGRAM_ID) { elevenDollars.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { elevenDollars.withNewOwner(newOwner = ALICE).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "The amount settled cannot be more than the amount outstanding."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = ALICE).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this.verifies()
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { tenDollars }
                output(CASH_PROGRAM_ID) { tenDollars.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { tenDollars.withNewOwner(newOwner = ALICE).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this.verifies()
            }
        }
    }

    @Test
    fun cashSettlementMustBeInTheCorrectCurrency() {
        val iou = IOUState(10.DOLLARS, ALICE, BOB)
        val tenDollars = createCashState(10.DOLLARS, BOB)
        val tenPounds = createCashState(10.POUNDS, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { tenPounds }
                output(CASH_PROGRAM_ID) { tenPounds.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { tenPounds.withNewOwner(newOwner = ALICE).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "Token mismatch: GBP vs USD"
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { tenDollars }
                output(CASH_PROGRAM_ID) { tenDollars.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { tenDollars.withNewOwner(newOwner = ALICE).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this.verifies()
            }
        }
    }


    @Test
    fun mustOnlyHaveOutputIOUIfNotFullySettling() {
        val iou = IOUState(10.DOLLARS, ALICE, BOB)
        val tenDollars = createCashState(10.DOLLARS, BOB)
        val fiveDollars = createCashState(5.DOLLARS, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "There must be one output IOU."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                verifies()
            }
            transaction {
                input(CASH_PROGRAM_ID) { tenDollars }
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.pay(10.DOLLARS) }
                output(CASH_PROGRAM_ID) { tenDollars.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { tenDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "There must be no output IOU as it has been fully settled."
            }
            transaction {
                input(CASH_PROGRAM_ID) { tenDollars }
                input(IOU_CONTRACT_ID) { iou }
                output(CASH_PROGRAM_ID) { tenDollars.withNewOwner(newOwner = ALICE).ownableState }
                command(BOB.owningKey) { tenDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                verifies()
            }
        }
    }

    @Test
    fun onlyPaidPropertyMayChange() {
        val iou = IOUState(10.DOLLARS, ALICE, BOB)
        val fiveDollars = createCashState(5.DOLLARS, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.copy(borrower = ALICE, paid = 5.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "The borrower may not change when settling."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.copy(amount = 0.DOLLARS, paid = 5.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "The amount may not change when settling."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.copy(lender = CHARLIE, paid = 5.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "The lender may not change when settling."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                verifies()
            }
        }
    }

    @Test
    fun paidMustBeCorrectlyUpdated() {
        val iou = IOUState(10.DOLLARS, ALICE, BOB)
        val fiveDollars = createCashState(5.DOLLARS, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.copy(paid = 4.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "Paid property incorrectly updated."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.copy() }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "Paid property incorrectly updated."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.copy(paid = 10.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                this `fails with` "Paid property incorrectly updated."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(CASH_PROGRAM_ID) { fiveDollars }
                output(CASH_PROGRAM_ID) { fiveDollars.withNewOwner(newOwner = ALICE).ownableState }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                command(BOB.owningKey) { fiveDollars.withNewOwner(newOwner = BOB).command }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                verifies()
            }
        }
    }

    @Test
    fun mustBeSignedByAllParticipants() {
        val iou = IOUState(10.DOLLARS, ALICE, BOB)
        val cash = createCashState(5.DOLLARS, BOB)
        val cashPayment = cash.withNewOwner(newOwner = ALICE)
        ledger {
            transaction {
                input(CASH_PROGRAM_ID) { cash }
                input(IOU_CONTRACT_ID) { iou }
                output(CASH_PROGRAM_ID) { cashPayment.ownableState }
                command(BOB.owningKey) { cashPayment.command }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                command(ALICE.owningKey, CHARLIE.owningKey) { IOUContract.Commands.Settle() }
                failsWith("Both lender and borrower together only must sign IOU settle transaction.")
            }
            transaction {
                input(CASH_PROGRAM_ID) { cash }
                input(IOU_CONTRACT_ID) { iou }
                output(CASH_PROGRAM_ID) { cashPayment.ownableState }
                command(BOB.owningKey) { cashPayment.command }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                command(BOB.owningKey) { IOUContract.Commands.Settle() }
                failsWith("Both lender and borrower together only must sign IOU settle transaction.")
            }
            transaction {
                input(CASH_PROGRAM_ID) { cash }
                input(IOU_CONTRACT_ID) { iou }
                output(CASH_PROGRAM_ID) { cashPayment.ownableState }
                command(BOB.owningKey) { cashPayment.command }
                output(IOU_CONTRACT_ID) { iou.pay(5.DOLLARS) }
                command(ALICE.owningKey, BOB.owningKey) { IOUContract.Commands.Settle() }
                verifies()
            }
        }
    }
}