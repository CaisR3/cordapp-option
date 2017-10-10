package net.corda.option.contract

import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.*
import net.corda.option.*
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
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
        val option = createOption(MEGA_CORP, MINI_CORP)
        val exercisedOption = option.copy(exercised = true, exercisedOnDate = Instant.now())

        ledger {
            unverifiedTransaction("Issue $3 to Mini Corp") {
                output(CASH_PROGRAM_ID, "Mini Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
            }

            transaction("Mega Corp issues an option to Mini Corp in exchange for $3") {
                input("Mini Corp's $3")
                output(OPTION_CONTRACT_ID, "Mini Corp's option", option)
                output(CASH_PROGRAM_ID, "Mega Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MEGA_CORP)
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Issue(KNOWN_SPOTS[0], KNOWN_VOLATILITIES[0]) }
                command(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
                timeWindow(TEST_TX_TIME)
                verifies()
            }

            transaction("Mini Corp sells the option back to Mega Corp for $3") {
                input("Mini Corp's option")
                input("Mega Corp's $3")
                output(OPTION_CONTRACT_ID, "Mega Corp's option") { "Mini Corp's option".output<OptionState>().copy(owner = MEGA_CORP) }
                output(CASH_PROGRAM_ID, "Mini Corp's new $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Trade(KNOWN_SPOTS[0], KNOWN_VOLATILITIES[0]) }
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Move() }
                timeWindow(TEST_TX_TIME)
                verifies()
            }

            transaction("Mega Corp exercises its option") {
                input("Mega Corp's option")
                output(OPTION_CONTRACT_ID, "Mega Corp's exercised option", exercisedOption)
                command(MEGA_CORP_PUBKEY) { OptionContract.Commands.Exercise() }
                timeWindow(TEST_TX_TIME)
                verifies()
            }
        }
    }
}