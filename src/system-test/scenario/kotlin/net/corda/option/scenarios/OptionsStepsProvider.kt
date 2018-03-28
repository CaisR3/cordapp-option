package net.corda.option.scenarios

import net.corda.behave.scenarios.api.StepsBlock
import net.corda.behave.scenarios.api.StepsProvider
import net.corda.option.scenarios.steps.OptionsSteps

class OptionsStepsProvider : StepsProvider {

    override val name: String
        get() = OptionsStepsProvider::javaClass.name

    override val stepsDefinition: StepsBlock
        get() = OptionsSteps()
}