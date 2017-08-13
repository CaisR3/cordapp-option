package net.corda.option.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import net.corda.option.datatypes.AttributeOf
import net.corda.option.datatypes.Vol

// Simple flow which takes a reference to an Oracle and a number then returns the corresponding vol number.
@InitiatingFlow
class QueryVol(val oracle: Party, val attributeOf: AttributeOf) : FlowLogic<Vol>() {
    @Suspendable override fun call() = sendAndReceive<Vol>(oracle, attributeOf).unwrap { it } }

