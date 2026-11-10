// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.ringloom.service.RingloomService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ControlAgentTest {
    @Test
    void doWorkRunsAfterPollHookAndSumsWork() {
        RingloomService service = mock(RingloomService.class);
        when(service.pollControl(16)).thenReturn(3);
        AtomicInteger hookCalls = new AtomicInteger();
        ControlAgent agent = new ControlAgent("control", service, 16, () -> {
            hookCalls.incrementAndGet();
            return 2;
        });
        assertThat(agent.doWork()).isEqualTo(5);
        assertThat(agent.doWork()).isEqualTo(5);
        assertThat(hookCalls.get()).isEqualTo(2);
        assertThat(agent.roleName()).isEqualTo("control");
    }

    @Test
    void doWorkWithoutHookReturnsPollWork() {
        RingloomService service = mock(RingloomService.class);
        when(service.pollControl(8)).thenReturn(4);
        assertThat(new ControlAgent(service, 8).doWork()).isEqualTo(4);
    }
}
