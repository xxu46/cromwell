#!/usr/bin/env bash

shutdown() {
    echo "Assembly log is:"
    cat ${ASSEMBLY_LOG}
    echo "Cromwell log is: "
    cat ${CROMWELL_LOG}
    echo "Centaur log is: "
    cat ${CENTAUR_LOG}
    cd "${INITIAL_DIR}"
    # This will take out the backgrounded Cromwell instance
    pkill -P $$
    exit "${EXIT_CODE}"
}

trap "shutdown" EXIT

set -e

EXIT_CODE=1

# remove CROMWELL_BRANCH
CROMWELL_BRANCH="${TRAVIS_BRANCH}"
INITIAL_DIR=$(pwd)

LOG_DIR="${INITIAL_DIR}/logs"
echo "Logging dir is ${LOG_DIR}"
ASSEMBLY_LOG=${LOG_DIR}/cromwell_assembly.log
CROMWELL_LOG=${LOG_DIR}/cromwell.log
CENTAUR_LOG=${LOG_DIR}/centaur.log

mkdir -p ${LOG_DIR}

echo "Building Cromwell"
sbt assembly >> ${ASSEMBLY_LOG} 2>&1

CROMWELL_JAR="${INITIAL_DIR}/cromwell/target/scala-2.11/cromwell-*.jar"

echo "Starting Cromwell, jar is ${CROMWELL_JAR}"
java -jar "${CROMWELL_JAR}" server >> "${CROMWELL_LOG}" 2>&1 &

# Build and run centaur
git clone https://github.com/broadinstitute/centaur.git
cd centaur
TEST_STATUS="failed"
echo "Running Centaur"
./run_tests_parallel.sh 5 >> ${CENTAUR_LOG} 2>&1

${TEST_COMMAND}  >> ../${CENTAUR_LOG} 2>&1

if [ $? -eq 0 ]; then
    EXIT_CODE=0
    TEST_STATUS="succeeded"
fi

echo "Centaur run ${TEST_STATUS}"
