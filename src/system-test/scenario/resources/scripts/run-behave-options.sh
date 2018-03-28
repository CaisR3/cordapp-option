#!/bin/bash

#
# Run this script from the top-level project directory
#
# $ pwd
# ./IdeaProjects/cordapp-option
# $ src/system-test/scenario/resources/scripts/run-behave-options.sh

CURRENT_DIR=$PWD

BEHAVE_DIR=/Users/josecoll/IdeaProjects/corda-reviews/experimental/behave/build/libs

./gradlew scenarioJar

java -cp "$BEHAVE_DIR/corda-behave.jar:$CURRENT_DIR/build/libs/cordapp-option-behave-test.jar" net.corda.behave.scenarios.ScenarioRunner --glue net.corda.behave.scenarios -path ./src/system-test/scenario/resources/features/options.feature -d
# -d to perform dry-run