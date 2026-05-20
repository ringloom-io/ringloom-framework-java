// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.CompositeAgent;
import org.agrona.concurrent.IdleStrategy;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class EventLoopTest {
    @Test
    void compositeAgentRunsAllChildren() throws Exception {
        // Given
        CompositeAgent agent = new CompositeAgent(namedAgent("one", () -> 40), namedAgent("two", () -> 2));

        // When
        int workCount = agent.doWork();

        // Then
        assertThat(workCount).isEqualTo(42);
    }

    @Test
    void eventLoopCallsLifecycleHooksOnce() throws Exception {
        // Given
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger work = new AtomicInteger();
        Agent agent = new Agent() {
            @Override
            public int doWork() {
                work.incrementAndGet();
                return 0;
            }

            @Override
            public void onStart() {
                starts.incrementAndGet();
            }

            @Override
            public void onClose() {
                closes.incrementAndGet();
            }

            @Override
            public String roleName() {
                return "test-agent";
            }
        };
        IdleStrategy idleStrategy = mock(IdleStrategy.class);
        EventLoop loop = new EventLoop("test", agent, idleStrategy, LoggerFactory.getLogger(EventLoopTest.class));
        ThreadFactory factory = Thread.ofPlatform().name("ringloom-test-loop").factory();

        // When
        loop.startThread(factory);
        while (work.get() == 0) {
            Thread.sleep(1);
        }
        loop.close();

        // Then
        assertThat(starts.get()).isEqualTo(1);
        assertThat(closes.get()).isEqualTo(1);
        assertThat(work.get()).isPositive();
        verify(idleStrategy, timeout(1_000).atLeastOnce()).idle(0);
    }

    private static Agent namedAgent(String roleName, Work work) {
        return new Agent() {
            @Override
            public int doWork() throws Exception {
                return work.doWork();
            }

            @Override
            public String roleName() {
                return roleName;
            }
        };
    }

    @FunctionalInterface
    private interface Work {
        int doWork() throws Exception;
    }
}
