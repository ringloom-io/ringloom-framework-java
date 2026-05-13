# SPDX-License-Identifier: Apache-2.0
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
ORDERS="${ORDERS:-1000}"
RATE_PER_SEC="${RATE_PER_SEC:-10000}"

cleanup() {
    if [ -n "${PIDS:-}" ]; then
        kill $PIDS >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

cd "$ROOT_DIR"
PIDS=""

./gradlew :samples:order-management-avaje:runPortfolioService &
PIDS="$PIDS $!"
sleep 0.5
./gradlew :samples:order-management-avaje:runExecutionService &
PIDS="$PIDS $!"
sleep 0.5
./gradlew :samples:order-management-avaje:runMatchingEngine &
PIDS="$PIDS $!"
sleep 0.5
./gradlew :samples:order-management-avaje:runRiskService &
PIDS="$PIDS $!"
sleep 0.5
./gradlew :samples:order-management-avaje:runOrderGateway &
PIDS="$PIDS $!"
sleep 2

./gradlew :samples:order-management-avaje:runOrderSimulator --args="$ORDERS $RATE_PER_SEC"
