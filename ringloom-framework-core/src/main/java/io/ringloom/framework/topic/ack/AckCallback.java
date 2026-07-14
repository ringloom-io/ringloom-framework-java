// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic.ack;

/**
 * Stateless callback invoked when a pending {@code replicate_once} topic publish completes.
 *
 * <p>Implementations are expected to be caller-reused singletons (no capturing lambdas on the hot
 * path), mirroring {@link io.ringloom.framework.request.ResponseCallback}. The callback runs inline on
 * the framework control thread.
 */
@FunctionalInterface
public interface AckCallback {
    /**
     * Invoked exactly once per registered publish.
     *
     * @param publishIndex the publish index returned by the publish call
     * @param status       the completion outcome
     * @param userContext  the caller-supplied opaque context
     */
    void onComplete(long publishIndex, AckStatus status, Object userContext);
}
