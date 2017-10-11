package net.corda.option.contract

import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.*
import net.corda.option.KNOWN_SPOTS
import net.corda.option.KNOWN_VOLATILITIES
import net.corda.option.contract.OptionContract.Companion.OPTION_CONTRACT_ID
import net.corda.option.createOption
import net.corda.option.state.OptionState
import net.corda.testing.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
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

    // TODO: These tests only test the golden path. Many more tests could be added to test various contract violations
    // TODO: (e.g. insufficient cash, bad oracle data, etc.)
    @Test
    fun `transaction tests`() {
        val issuer = MEGA_CORP.ref(123)
        val option = createOption(MEGA_CORP, MINI_CORP)
        option.spotPriceAtPurchase = 3.DOLLARS
        // By the point of exercise, the option has already been transferred.
        val exercisedOption = option.copy(owner = MEGA_CORP, exercised = true, exercisedOnDate = Instant.now())

        ledger {
            unverifiedTransaction("Issue $9 to Mini Corp") {
                output(CASH_PROGRAM_ID, "Mini Corp's $9", 9.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
            }

            transaction("Mega Corp issues an option to Mini Corp in exchange for $9") {
                input("Mini Corp's $9")
                output(OPTION_CONTRACT_ID, "Mini Corp's option", option)
                output(CASH_PROGRAM_ID, "Mega Corp's $9", 9.DOLLARS.CASH `issued by` issuer `owned by` MEGA_CORP)
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Issue() }
                command(ORACLE_PUBKEY) { OptionContract.OracleCommand(KNOWN_SPOTS[0], KNOWN_VOLATILITIES[0]) }
                command(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
                timeWindow(Instant.now(), Duration.ofSeconds(60))
                verifies()
            }

            transaction("Mini Corp sells the option back to Mega Corp for $9") {
                input("Mini Corp's option")
                input("Mega Corp's $9")
                output(OPTION_CONTRACT_ID, "Mega Corp's option") { "Mini Corp's option".output<OptionState>().copy(owner = MEGA_CORP) }
                output(CASH_PROGRAM_ID, "Mini Corp's new $9", 9.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Trade() }
                command(ORACLE_PUBKEY) { OptionContract.OracleCommand(KNOWN_SPOTS[0], KNOWN_VOLATILITIES[0]) }
                command(MEGA_CORP_PUBKEY) { Cash.Commands.Move() }
                timeWindow(Instant.now(), Duration.ofSeconds(60))
                verifies()
            }

            transaction("Mega Corp exercises its option") {
                input("Mega Corp's option")
                output(OPTION_CONTRACT_ID, "Mega Corp's exercised option", exercisedOption)
                command(MEGA_CORP_PUBKEY) { OptionContract.Commands.Exercise() }
                timeWindow(Instant.now(), Duration.ofSeconds(60))
                verifies()
            }
        }
    }
}