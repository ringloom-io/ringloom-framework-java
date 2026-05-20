// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import io.ringloom.service.RingloomService;
import java.util.Objects;
import org.agrona.concurrent.Agent;

/**
 * Agent that polls the native RingLoom control channel.
 */
public final class ControlAgent implements Agent {
    private final String roleName;
    private final RingloomService service;
    private final int pollLimit;

    public ControlAgent(RingloomService service, int pollLimit) {
        this("ringloom-control-agent", service, pollLimit);
    }

    public ControlAgent(String roleName, RingloomService service, int pollLimit) {
        this.roleName = Objects.requireNonNullElse(roleName, "ringloom-control-agent");
        this.service = Objects.requireNonNull(service, "service");
        if (pollLimit < 0) {
            throw new IllegalArgumentException("pollLimit must be non-negative");
        }
        this.pollLimit = pollLimit;
    }

    @Override
    public int doWork() {
        return service.pollControl(pollLimit);
    }

    @Override
    public String roleName() {
        return roleName;
    }
}
