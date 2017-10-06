package net.corda.option.contract

import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.finance.DOLLARS
import net.corda.finance.POUNDS
import net.corda.option.contract.IOUContract.Companion.IOU_CONTRACT_ID
import net.corda.option.state.IOUState
import net.corda.testing.*
import net.corda.testing.contracts.DUMMY_PROGRAM_ID
import org.junit.Test

class IOUTransferTests {
    // A pre-made dummy state we may need for some of the tests.
    class DummyState : ContractState {
        override val participants: List<AbstractParty> get() = listOf()
    }

    @Test
    fun mustHaveOneInputAndOneOutput() {
        val iou = IOUState(1.POUNDS, ALICE, BOB)

        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                input(DUMMY_PROGRAM_ID) { DummyState() }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "An IOU transfer transaction should only consume one input state."
            }
            transaction {
                output(IOU_CONTRACT_ID) { iou }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "An IOU transfer transaction should only consume one input state."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "An IOU transfer transaction should only create one output state."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                output(DUMMY_PROGRAM_ID) { DummyState() }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "An IOU transfer transaction should only create one output state."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this.verifies()
            }
        }
    }

    @Test
    fun onlyTheLenderMayChange() {
        val iou = IOUState(1.POUNDS, ALICE, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { IOUState(10.DOLLARS, ALICE, BOB) }
                output(IOU_CONTRACT_ID) { IOUState(1.DOLLARS, ALICE, BOB) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "Only the lender property may change."
            }
            transaction {
                input(IOU_CONTRACT_ID) { IOUState(10.DOLLARS, ALICE, BOB) }
                output(IOU_CONTRACT_ID) { IOUState(10.DOLLARS, ALICE, CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "Only the lender property may change."
            }
            transaction {
                input(IOU_CONTRACT_ID) { IOUState(10.DOLLARS, ALICE, BOB, 5.DOLLARS) }
                output(IOU_CONTRACT_ID) { IOUState(10.DOLLARS, ALICE, BOB, 10.DOLLARS) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "Only the lender property may change."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this.verifies()
            }
        }
    }

    @Test
    fun theLenderMustChange() {
        val iou = IOUState(1.POUNDS, ALICE, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "The lender property must change in a transfer."
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this.verifies()
            }
        }
    }

    @Test
    fun allParticipantsMustSign() {
        val iou = IOUState(1.POUNDS, ALICE, BOB)
        ledger {
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "The borrower, old lender and new lender only must sign an IOU transfer transaction"
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "The borrower, old lender and new lender only must sign an IOU transfer transaction"
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "The borrower, old lender and new lender only must sign an IOU transfer transaction"
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, MINI_CORP_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "The borrower, old lender and new lender only must sign an IOU transfer transaction"
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY, MINI_CORP_PUBKEY) { IOUContract.Commands.Transfer() }
                this `fails with` "The borrower, old lender and new lender only must sign an IOU transfer transaction"
            }
            transaction {
                input(IOU_CONTRACT_ID) { iou }
                output(IOU_CONTRACT_ID) { iou.withNewLender(CHARLIE) }
                command(ALICE_PUBKEY, BOB_PUBKEY, CHARLIE_PUBKEY) { IOUContract.Commands.Transfer() }
                this.verifies()
            }
        }
    }
}