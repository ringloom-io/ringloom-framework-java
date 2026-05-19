# SPDX-License-Identifier: Apache-2.0
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)

cleanup() {
    if [ -n "${PIDS:-}" ]; then
        kill $PIDS >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

cd "$ROOT_DIR"
PIDS=""

./gradlew :samples:market-data-avaje-sbe:runPricingService &
PIDS="$PIDS $!"
sleep 2

./gradlew :samples:market-data-avaje-sbe:runMarketTerminal
