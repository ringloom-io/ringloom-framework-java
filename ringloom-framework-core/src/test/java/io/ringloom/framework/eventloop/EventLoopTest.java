// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventLoopTest {
    @Test
    void compositeAgentSaturatesWorkCount() {
        CompositeAgent agent = new CompositeAgent("test", () -> Integer.MAX_VALUE, () -> 42);

        assertEquals(Integer.MAX_VALUE, agent.doWork());
    }

    @Test
    void eventLoopCallsLifecycleHooksOnce() throws Exception {
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
        };
        EventLoop loop = new EventLoop("test", agent, new SleepingIdleStrategy(TimeUnit.MILLISECONDS.toNanos(1)), LoggerFactory.getLogger(EventLoopTest.class));
        ThreadFactory factory = Thread.ofPlatform().name("ringloom-test-loop").factory();

        loop.startThread(factory);
        while (work.get() == 0) {
            Thread.sleep(1);
        }
        loop.close();

        assertEquals(1, starts.get());
        assertEquals(1, closes.get());
        assertTrue(work.get() > 0);
    }
}
