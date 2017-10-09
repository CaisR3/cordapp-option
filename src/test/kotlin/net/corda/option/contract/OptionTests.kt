package net.corda.option.contract

import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.*
import net.corda.option.SpotPrice
import net.corda.option.Stock
import net.corda.option.contract.IOUContract.Companion.IOU_CONTRACT_ID
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.createOption
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import net.corda.testing.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class OptionTransactionTests {

    @Before
    fun setup() {
        setCordappPackages("net.corda.finance.contracts.asset", "net.corda.option.contract")
    }

    @After
    fun tearDown() {
        unsetCordappPackages()
    }

    @Test
    fun `transaction tests`() {
        val issuer = MEGA_CORP.ref(123)
        val option = createOption(MEGA_CORP, MEGA_CORP)
        val iou = IOUState(9.DOLLARS, MINI_CORP, MEGA_CORP)
        val spotValue = 1.DOLLARS
        val spot = SpotPrice(Stock(option.underlyingStock, TEST_TX_TIME), spotValue)

        ledger {
            unverifiedTransaction("Issue Mini Corp's $3") {
                output(CASH_PROGRAM_ID, "Mini Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
            }

            transaction("Issue Mega Corp's option") {
                output(OPTION_CONTRACT_ID, "Mega Corp's option", option)
                command(MEGA_CORP_PUBKEY, OptionContract.Commands.Issue())
                this.timeWindow(TEST_TX_TIME)
                this.verifies()
            }

            transaction("Trade Mega Corp's option for Mini Corp's $3") {
                input("Mega Corp's option")
                input("Mini Corp's $3")
                output(CASH_PROGRAM_ID, "Mega Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MEGA_CORP)
                output(OPTION_CONTRACT_ID, "Mini Corp's option") { "Mega Corp's option".output<OptionState>() `owned by` MINI_CORP }
                command(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Trade() }
                this.verifies()
            }

            transaction("Exercise Mini Corp's option and receive an IOU of $9 from Mega Corp") {
                input("Mini Corp's option")
                output(OPTION_CONTRACT_ID, "Mini Corp's exercised option") { "Mini Corp's option".output<OptionState>().exercise(5.DOLLARS) `owned by` MINI_CORP }
                output(IOU_CONTRACT_ID, "Mini Corp's IOU from Mega Corp", iou)
                command(MINI_CORP_PUBKEY) { OptionContract.Commands.Exercise(spot) }
                command(MINI_CORP_PUBKEY) { IOUContract.Commands.Issue()}
                this.timeWindow(TEST_TX_TIME)
                this.verifies()
            }
        }
    }
}