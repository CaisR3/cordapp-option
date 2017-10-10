package net.corda.option.contract

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.option.OptionType
import net.corda.option.SpotPrice
import net.corda.option.state.IOUState
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
                    "A cash input is consumed" using (tx.inputsOfType<Cash.State>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "An option is issued onto the ledger" using (tx.outputsOfType<OptionState>().size == 1)
                    "Cash is transferred" using (tx.outputsOfType<Cash.State>().size == 1)
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
                    "An option is consumed" using (tx.inputsOfType<OptionState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "An new option is created" using (tx.outputsOfType<OptionState>().size == 1)
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
                    "An option is consumed" using (tx.inputsOfType<OptionState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "An IOU is created" using (tx.outputsOfType<IOUState>().size == 1)
                    "No other states are created" using (tx.outputs.size == 1)
                    "Exercises of options must be timestamped" using (tx.timeWindow?.fromTime != null)

                    val input = tx.inputsOfType<OptionState>().single()
                    val output = tx.outputsOfType<IOUState>().single()
                    val spotPrice = (command.value as Commands.Exercise).spot.value

                    "The option is being exercised before maturity" using (tx.timeWindow!!.untilTime!! <= input.expiryDate)

                    when (input.optionType) {
                        OptionType.CALL ->
                            "The IOU amount equals the spot minus the strike for a Call option" using
                                    (output.amount == OptionState.calculateMoneyness(input.strikePrice, spotPrice, input.optionType))
                        OptionType.PUT ->
                            "The IOU amount equals the strike minus the spot for a Put option" using
                                    (output.amount == OptionState.calculateMoneyness(input.strikePrice, spotPrice, input.optionType))
                    }

                    "Exercising the option is signed by the current owner of the option" using (input.owner.owningKey in command.signers)
                }
            }

            else -> throw IllegalArgumentException("Unknown command.")
        }
    }

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Trade : TypeOnlyCommandData(), Commands
        class Exercise(val spot: SpotPrice) : Commands
    }
}
