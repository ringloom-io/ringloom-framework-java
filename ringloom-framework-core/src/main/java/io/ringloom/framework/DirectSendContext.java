// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import io.ringloom.service.BufferClaim;
import io.ringloom.service.RingloomClient;

/**
 * Reusable claim holder for direct-send client APIs that want to avoid allocating a new
 * {@link BufferClaim} per call.
 */
public final class DirectSendContext implements AutoCloseable {
    private BufferClaim claim;

    /**
     * Returns the reusable claim for a client, creating it on first use.
     *
     * @param client the low-level client that owns the claim
     * @return the reusable buffer claim
     */
    public BufferClaim claim(RingloomClient client) {
        if (claim == null) {
            claim = client.newClaim();
        }
        return claim;
    }

    /**
     * Releases the cached claim, if any.
     */
    @Override
    public void close() {
        if (claim != null) {
            claim.close();
            claim = null;
        }
    }
}
