// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import io.ringloom.service.RingloomService;
import java.util.Objects;

/**
 * Agent that polls the native RingLoom control channel.
 */
public final class ControlAgent implements Agent {
    private final RingloomService service;
    private final int pollLimit;

    public ControlAgent(RingloomService service, int pollLimit) {
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
}
