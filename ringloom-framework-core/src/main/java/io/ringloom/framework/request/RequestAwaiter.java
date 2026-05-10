// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import java.util.concurrent.locks.LockSupport;

/**
 * Lightweight thread parker used by blocking request APIs.
 */
public final class RequestAwaiter {
    private volatile Thread waiter;
    private volatile boolean complete;

    public void prepare(Thread thread) {
        waiter = thread;
        complete = false;
    }

    public void complete() {
        complete = true;
        Thread thread = waiter;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    public boolean awaitNanos(long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        while (!complete) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            LockSupport.parkNanos(this, remaining);
        }
        return true;
    }
}
