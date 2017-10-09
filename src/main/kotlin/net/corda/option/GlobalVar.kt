package net.corda.option

import net.corda.core.identity.CordaX500Name
import java.time.Instant

val ORACLE_NAME = CordaX500Name("PartyD", "New York","US")
val DEMO_INSTANT = Instant.parse("2017-07-03T10:15:30.00Z")!!
val RISK_FREE_RATE = 0.01
val SPOTS_TXT_FILE = "oracle/example.spots.txt"
val VOLS_TXT_FILE = "oracle/example.vols.txt"