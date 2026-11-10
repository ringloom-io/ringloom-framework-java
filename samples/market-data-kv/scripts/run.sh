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

./gradlew -q :samples:market-data-kv:writerClasses :samples:market-data-kv:readerClasses \
    :samples:market-data-kv:terminalClasses

# The reader starts first: its store stays pending (reads return null) until a writer publishes.
./gradlew -q :samples:market-data-kv:runPriceReader &
PIDS="$PIDS $!"
sleep 3

# Two writers: the first holds the lease, the second queues as a standby (acquire: wait).
./gradlew -q :samples:market-data-kv:runPriceWriter &
PIDS="$PIDS $!"
sleep 3
./gradlew -q :samples:market-data-kv:runPriceWriter &
PIDS="$PIDS $!"
sleep 3

./gradlew -q :samples:market-data-kv:runPriceTerminal --args="${ROUNDS:-120}"
