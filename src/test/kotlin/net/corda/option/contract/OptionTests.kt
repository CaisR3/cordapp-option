package net.corda.option.contract

import net.corda.core.utilities.days
import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.*
import net.corda.option.OptionType
import net.corda.option.SpotPrice
import net.corda.option.Stock
import net.corda.option.contract.IOUContract
import net.corda.option.contract.IOUContract.Companion.IOU_CONTRACT_ID
import net.corda.option.contract.OptionContract
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import net.corda.testing.*
import org.junit.Test
import java.util.*

class OptionTransactionTests {

    @Test
    fun `transaction tests`() {
        val issuer = MEGA_CORP.ref(123)
        val option = getOption()
        val iou = IOUState(9.DOLLARS, MINI_CORP, MEGA_CORP)
        val spotValue = 1.DOLLARS
        val spot = SpotPrice(Stock(option.underlyingStock, TEST_TX_TIME), spotValue)

        ledger {
            unverifiedTransaction {
                output(CASH_PROGRAM_ID, "Mini Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
            }

            transaction("Issuance") {
                output(OPTION_CONTRACT_ID, "option") { option }
                command(MEGA_CORP_PUBKEY) { OptionContract.Commands.Issue() }
                this.timeWindow(TEST_TX_TIME)
                this.verifies()
            }

            transaction("Trade") {
                input("option")
                input("Mini Corp's $3")
                output(CASH_PROGRAM_ID, "cash of $3") { 3.DOLLARS.CASH `issued by` issuer `owned by` MEGA_CORP }
                output(OPTION_CONTRACT_ID, "Mi ni Corp's option") { "option".output<OptionState>() `owned by` MINI_CORP }
                command(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Trade() }
                this.verifies()
            }

            transaction("Exercise") {
                input("Mini Corp's option")
                output(OPTION_CONTRACT_ID, "Mini Corp's exercised option") { "Mini Corp's option".output<OptionState>().exercise(5.DOLLARS) `owned by` MINI_CORP }
                output(IOU_CONTRACT_ID, iou)
                command(MINI_CORP_PUBKEY) { OptionContract.Commands.Exercise(spot) }
                command(MINI_CORP_PUBKEY) { IOUContract.Commands.Issue()}
                this.timeWindow(TEST_TX_TIME)
                this.verifies()
            }
        }
    }

    fun getOption() : OptionState = OptionState(
            strike = 10.DOLLARS,
            expiry = TEST_TX_TIME + 30.days,
            currency = Currency.getInstance("USD"),
            underlyingStock = "IBM",
            issuer = MEGA_CORP,
            owner = MEGA_CORP,
            optionType = OptionType.PUT
    )
}