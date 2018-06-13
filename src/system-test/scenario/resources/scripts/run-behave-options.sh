#!/bin/bash

#
# Run this script from the top-level project directory
#
# $ pwd
# ./IdeaProjects/cordapp-option
# $ src/system-test/scenario/resources/scripts/run-behave-options.sh
#
# NOTE: remember to setup your staging environment using the `prepare` script of gradle task.
#

SAMPLE_HOME=$PWD
BEHAVE_DIR=${SAMPLE_HOME}/../../experimental/behave

cd ${BEHAVE_DIR}
BEHAVE_JAR=$(ls ${BEHAVE_DIR}/build/libs/corda-behave-[0-9]*.jar | tail -n1)

if [ ! -f "${BEHAVE_JAR}" ]; then
    echo "Building behaveJar ..."
    ../../gradlew behaveJar
fi
BEHAVE_JAR=$(ls ${BEHAVE_DIR}/build/libs/corda-behave-[0-9]*.jar | tail -n1)


cd ${SAMPLE_HOME}
SAMPLE_JAR=$(ls ${SAMPLE_HOME}/build/libs/cordapp-option-*.jar | tail -n1)
if [ ! -f "${SAMPLE_JAR}" ]; then
    echo "Building scenarioJar ..."
    ./gradlew scenarioJar
fi
SAMPLE_JAR=$(ls ${SAMPLE_HOME}/build/libs/cordapp-option-*.jar | tail -n1)

# QA interoperability
CMD="java -DSTAGING_ROOT=${STAGING_ROOT} -DDISABLE_CLEANUP=true -cp ${BEHAVE_JAR}:${SAMPLE_JAR} net.corda.behave.scenarios.ScenarioRunner -path ${SAMPLE_HOME}/src/system-test/scenario/resources/features/options.feature"
# -d to perform dry-run
echo "Executing: ${CMD}"
eval `${CMD}`
# -d to perform dry-run