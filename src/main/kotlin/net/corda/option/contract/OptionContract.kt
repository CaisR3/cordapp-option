package net.corda.option.contract

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.option.SpotPrice
import net.corda.option.state.OptionState

open class OptionContract : Contract {
    companion object {
        @JvmStatic
        val OPTION_CONTRACT_ID = "net.corda.option.contract.OptionContract"
    }

    override fun verify(tx: LedgerTransaction) {
        // We should only ever receive one command at a time, else throw an exception
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value) {
            is Commands.Issue -> {
                requireThat {
                    "A Cash.State input is consumed" using (tx.inputsOfType<Cash.State>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "An OptionState is created on the ledger" using (tx.outputsOfType<OptionState>().size == 1)
                    "The Cash.State is transferred" using (tx.outputsOfType<Cash.State>().size == 1)
                    "No other states are created" using (tx.outputs.size == 2)
                    "Option issuances must be timestamped" using (tx.timeWindow?.untilTime != null)

                    tx.commands.requireSingleCommand<Cash.Commands.Move>()

                    val optionOutput = tx.outputsOfType<OptionState>().single()
                    val time = tx.timeWindow!!.untilTime!!
                    "The strike must be non-negative" using (optionOutput.strikePrice.quantity > 0)
                    "The expiry date is not in the past" using (time < optionOutput.expiryDate)

                    "The issue transaction is signed by the issuer" using (optionOutput.issuer.owningKey in command.signers)
                    "The issue transaction is signed by the owner" using (optionOutput.owner.owningKey in command.signers)
                }
            }

            is Commands.Trade -> {
                requireThat {
                    "An OptionState is consumed" using (tx.inputsOfType<OptionState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "A new OptionState is created" using (tx.outputsOfType<OptionState>().size == 1)
                    "No other states are created" using (tx.outputs.size == 1)

                    val input = tx.inputsOfType<OptionState>().single()
                    val output = tx.outputsOfType<OptionState>().single()
                    "Only the owner property may change." using (input.copy(owner = output.owner) == output)
                    "The owner property must change." using (input.owner != output.owner)

                    "The trade is signed by the issuer of the option" using (input.issuer.owningKey in command.signers)
                    "The trade is signed by the current owner of the option" using (input.owner.owningKey in command.signers)
                    "The trade is signed by the new owner of the option" using (output.owner.owningKey in command.signers)
                }
            }

            is Commands.Exercise -> {
                requireThat {
                    "An OptionState is consumed" using (tx.inputsOfType<OptionState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "A new OptionState is created" using (tx.outputsOfType<OptionState>().size == 1)
                    "No other states are created" using (tx.outputs.size == 1)
                    "Exercises of options must be timestamped" using (tx.timeWindow?.fromTime != null)

                    val input = tx.inputsOfType<OptionState>().single()
                    val output = tx.outputsOfType<OptionState>().single()

                    "The option is being exercised before maturity" using (tx.timeWindow!!.untilTime!! <= input.expiryDate)
                    "The output option is exercised" using (output.exercised)

                    "Exercising the option is signed by the current owner of the option" using (input.owner.owningKey in command.signers)
                }
            }

            else -> throw IllegalArgumentException("Unknown command.")
        }
    }

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Trade : TypeOnlyCommandData(), Commands
        class Exercise : TypeOnlyCommandData(), Commands
        class Redeem(val spot: SpotPrice): Commands
    }
}
