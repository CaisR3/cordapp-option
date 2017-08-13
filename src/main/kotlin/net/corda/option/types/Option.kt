package net.corda.option.types

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
enum class OptionType {
    CALL, PUT
}