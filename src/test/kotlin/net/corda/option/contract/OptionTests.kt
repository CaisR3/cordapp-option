package com.option.contract

import net.corda.contracts.asset.CASH
import net.corda.contracts.asset.Cash
import net.corda.contracts.asset.`issued by`
import net.corda.contracts.asset.`owned by`
import net.corda.core.contracts.DOLLARS
import net.corda.core.contracts.POUNDS
import net.corda.core.days
import net.corda.core.utilities.ALICE
import net.corda.core.utilities.BOB
import net.corda.core.utilities.TEST_TX_TIME
import net.corda.option.contract.IOUContract
import net.corda.option.contract.OptionContract
import net.corda.option.datatypes.Spot
import net.corda.option.datatypes.AttributeOf
import net.corda.option.state.IOUState
import net.corda.option.state.OptionState
import net.corda.option.types.OptionType
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
        val spot = Spot(AttributeOf(option.underlying, TEST_TX_TIME), spotValue)

        ledger {
            unverifiedTransaction {
                output("Mini Corp's $3", 3.DOLLARS.CASH `issued by` issuer `owned by` MINI_CORP)
            }

            transaction("Issuance") {
                output("option") { option }
                command(MEGA_CORP_PUBKEY) { OptionContract.Commands.Issue() }
                this.timeWindow(TEST_TX_TIME)
                this.verifies()
            }

            transaction("Trade") {
                input("option")
                input("Mini Corp's $3")
                output("cash of $3") { 3.DOLLARS.CASH `issued by` issuer `owned by` MEGA_CORP }
                output("Mini Corp's option") { "option".output<OptionState>() `owned by` MINI_CORP }
                command(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
                command(MEGA_CORP_PUBKEY, MINI_CORP_PUBKEY) { OptionContract.Commands.Trade() }
                this.verifies()
            }

            transaction("Exercise") {
                input("Mini Corp's option")
                output("Mini Corp's exercised option") { "Mini Corp's option".output<OptionState>().exercise(5.DOLLARS) `owned by` MINI_CORP }
                output(iou)
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
            underlying = "IBM",
            issuer = MEGA_CORP,
            owner = MEGA_CORP,
            optionType = OptionType.PUT
    )
}