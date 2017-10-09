package net.corda.option.contract

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.option.OptionType
import net.corda.option.RISK_FREE_RATE
import net.corda.option.SpotPrice
import net.corda.option.pricingmodel.BlackScholes
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import java.util.*

open class OptionContract : Contract {
    companion object {
        @JvmStatic
        val OPTION_CONTRACT_ID = "net.corda.option.contract.OptionContract"

        fun calculateMoneyness(strike: Amount<Currency>, spot: Amount<Currency>, optionType: OptionType): Amount<Currency> {
            val zeroAmount = Amount.zero(spot.token)
            when {
                optionType == OptionType.CALL -> {
                    if (strike >= spot)
                        return zeroAmount
                    return spot - strike
                }
                spot >= strike -> return zeroAmount
                else -> return strike - spot
            }
        }

        fun calculatePremium(optionState: OptionState, volatility: Double): Double {
            // Assume risk free rate of 1%
            val blackScholes = BlackScholes(optionState.spotPrice.quantity.toDouble(), optionState.strike.quantity.toDouble(), RISK_FREE_RATE, 100.toDouble(), volatility)
            if (optionState.optionType == OptionType.CALL)
            {
                return blackScholes.BSCall()
            }
            return blackScholes.BSPut()
        }
    }

    /**
     * The verify() function of the contract of each of the transaction's input and output states must not throw an
     * exception for a transaction to be considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        // We should only ever receive one command at a time, else throw an exception
        val command = tx.commands.requireSingleCommand<Commands>()
        val timeWindow = tx.timeWindow

        when (command.value) {
            is Commands.Issue -> {
                val output = tx.outputsOfType<OptionState>().single()
                val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issued options must be timestamped")
                requireThat {
                    "the issue transaction is signed by the issuer" using (output.issuer.owningKey in command.signers)
                    "the issue transaction is signed by the owner" using (output.owner.owningKey in command.signers)

                    "The strike must be non-negative" using (output.strike.quantity > 0)
                    "the expiry date is not in the past" using (time < output.expiry)

                    "The state is propagated" using (tx.outputs.isNotEmpty())
                }
            }

            is Commands.Trade -> {
                val input = tx.inputsOfType<OptionState>().single()
                val output = tx.outputsOfType<OptionState>().single()

                requireThat {
                    "The trade is signed by the issuer of the option)" using (input.issuer.owningKey in command.signers)
                    "The trade is signed by the current owner of the option" using (input.owner.owningKey in command.signers)
                    "The trade is signed by the new owner of the option" using (output.owner.owningKey in command.signers)

                    "The option cannot be traded if already exercised" using (!input.exercised)

                    "Only the owner property may change." using (input.strike == output.strike && input.issuer == output.issuer && input.currency == output.currency
                            && input.expiry == output.expiry && input.underlyingStock == output.underlyingStock)
                    "The owner property must change in a trade." using (input.owner != output.owner)

                    "The state is propagated" using (tx.outputs.isNotEmpty())
                }
            }

            is Commands.Exercise -> {
                val commandValue = command.value as Commands.Exercise
                val input = tx.inputsOfType<OptionState>().single()
                // We expect an IOU state as an output on exercising that matches the moneyness
                val optionOutput = tx.outputsOfType<OptionState>().single()
                val iouOutput = tx.outputsOfType<IOUState>().single()
                val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Exercising of the option must be timestamped")

                requireThat {
                    //Exercising the option is constrained by the expiry
                    "Exercising the option is executed before maturity" using (time <= input.expiry)
                    "The option is not already exercised" using (!input.exercised)

                    when (input.optionType) {
                        OptionType.CALL ->
                            "The IOU amount equals the spot minus the strike for a Call option" using (iouOutput.amount == calculateMoneyness(input.strike, commandValue.spot.value, input.optionType))
                        OptionType.PUT ->
                            "The IOU amount equals the strike minus the spot for a Put option" using (iouOutput.amount == calculateMoneyness(input.strike, commandValue.spot.value, input.optionType))
                    }

                    "Exercising the option is signed by the current owner of the option" using (input.owner.owningKey in command.signers)

                    "The option must be set to exercised" using (optionOutput.exercised)
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
