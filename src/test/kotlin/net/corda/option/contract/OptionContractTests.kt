package net.corda.option.contract

import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.*
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.createOption
import net.corda.option.state.OptionState
import net.corda.testing.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant

class OptionContractTests {

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
        val exercisedOption = option.copy(exercised = true, exercisedOnDate = Instant.now())

        ledger {
            unverifiedTransaction("Issue Mini Corp's $3") {
                output(CASH_PROGRAM_ID, "Mini Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
            }

            transaction("Issue Mega Corp's option") {
                input("Mini Corp's $3")
                output(OPTION_CONTRACT_ID, "Mega Corp's option", option)
                output(CASH_PROGRAM_ID, "Mega Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MEGA_CORP)
                command(MEGA_CORP_PUBKEY, OptionContract.Commands.Issue())
                command(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
                timeWindow(TEST_TX_TIME)
                verifies()
            }

            transaction("Trade Mega Corp's option for Mini Corp's $3") {
                input("Mega Corp's option")
                output(OPTION_CONTRACT_ID, "Mini Corp's option") { "Mega Corp's option".output<OptionState>().copy(owner = MINI_CORP) }
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Trade() }
                verifies()
            }

            transaction("Exercise Mini Corp's option and receive an IOU of $9 from Mega Corp") {
                input("Mini Corp's option")
                output(OPTION_CONTRACT_ID, "Mini Corp's IOU from Mega Corp", exercisedOption)
                command(MINI_CORP_PUBKEY) { OptionContract.Commands.Exercise() }
                timeWindow(TEST_TX_TIME)
                verifies()
            }
        }
    }
}