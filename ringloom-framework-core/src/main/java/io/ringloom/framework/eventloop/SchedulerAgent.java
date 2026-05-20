// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import io.ringloom.framework.scheduler.RingloomScheduler;
import java.util.Objects;
import org.agrona.concurrent.Agent;

/**
 * Agent that expires scheduler timers on the control thread.
 */
public final class SchedulerAgent implements Agent {
    private final RingloomScheduler scheduler;

    public SchedulerAgent(RingloomScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public int doWork() throws Exception {
        return scheduler.poll(System.nanoTime());
    }

    @Override
    public String roleName() {
        return "ringloom-scheduler-agent";
    }
}
