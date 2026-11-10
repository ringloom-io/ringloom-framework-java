// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import io.ringloom.service.RingloomService;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.agrona.concurrent.Agent;

/**
 * Agent that polls the native RingLoom control channel and then runs an optional post-poll hook
 * (topic ack feedback, KV control work) on the same thread.
 */
public final class ControlAgent implements Agent {
    private final String roleName;
    private final RingloomService service;
    private final int pollLimit;
    private final IntSupplier afterPoll;

    public ControlAgent(RingloomService service, int pollLimit) {
        this("ringloom-control-agent", service, pollLimit);
    }

    public ControlAgent(String roleName, RingloomService service, int pollLimit) {
        this(roleName, service, pollLimit, null);
    }

    /**
     * Creates a control agent with a post-poll hook.
     *
     * @param roleName  the agent role name
     * @param service   the native service to poll
     * @param pollLimit the control poll limit
     * @param afterPoll invoked after every native control poll on the control thread; returns the
     *                  amount of work done (may be {@code null})
     */
    public ControlAgent(String roleName, RingloomService service, int pollLimit, IntSupplier afterPoll) {
        this.roleName = Objects.requireNonNullElse(roleName, "ringloom-control-agent");
        this.service = Objects.requireNonNull(service, "service");
        if (pollLimit < 0) {
            throw new IllegalArgumentException("pollLimit must be non-negative");
        }
        this.pollLimit = pollLimit;
        this.afterPoll = afterPoll;
    }

    @Override
    public int doWork() {
        int work = service.pollControl(pollLimit);
        IntSupplier hook = afterPoll;
        return hook == null ? work : work + hook.getAsInt();
    }

    @Override
    public String roleName() {
        return roleName;
    }
}
