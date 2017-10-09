package net.corda.option.contract

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.option.state.IOUState

/**
 * The IOUContract only handles issuing a new [IOUState] on the ledger, which is a bilateral agreement between two
 * parties. This functionality is sufficient for this sample.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "net.corda.option.contract.IOUContract"
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
    }

    /**
     * The contract code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<IOUContract.Commands>()
        when (command.value) {
            is Commands.Issue -> requireThat {
                "Only one input state should be consumed when issuing an IOU." using (tx.inputs.size == 1)
                "Two output states should be created when issuing an IOU." using (tx.outputs.size == 2)
                val iou = tx.outputsOfType<IOUState>().single()
                "The issued IOU must have a positive amount." using (iou.amount > Amount(0, iou.amount.token))
                "The lender and borrower cannot be the same party." using (iou.borrower != iou.lender)
                "Only the lender must sign this IOU." using (command.signers.single() == iou.lender.owningKey)
            }
        }
    }
}