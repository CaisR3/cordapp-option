package net.corda.option.contact

import net.corda.core.contracts.Amount
import net.corda.core.contracts.Contract
import net.corda.option.state.OptionState
import net.corda.option.types.OptionType
import java.util.*

/**
 * Created by Cais Manai on 12/07/2017.
 */
interface IOptionContract : Contract {
    fun calculateMoneyness(strike : Amount<Currency>, spot : Amount<Currency>, optionType: OptionType) : Amount<Currency>
    fun calculatePremium(optionState : OptionState, volatility : Double) : Double
}