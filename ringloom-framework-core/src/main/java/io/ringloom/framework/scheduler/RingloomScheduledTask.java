// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.scheduler;

import io.ringloom.framework.RingloomRuntime;

/**
 * Callback invoked by the RingLoom control-loop scheduler when a timer expires.
 */
@FunctionalInterface
public interface RingloomScheduledTask {
    /**
     * Runs scheduled work on the runtime control thread.
     *
     * @param runtime the owning runtime
     * @throws Exception when scheduled work fails; failures stop the owning event loop
     */
    void onSchedule(RingloomRuntime runtime) throws Exception;
}
