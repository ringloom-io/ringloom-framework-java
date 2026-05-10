// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import io.ringloom.service.BufferClaim;
import io.ringloom.service.RingloomClient;

public final class DirectSendContext implements AutoCloseable {
    private BufferClaim claim;

    public BufferClaim claim(RingloomClient client) {
        if (claim == null) {
            claim = client.newClaim();
        }
        return claim;
    }

    @Override
    public void close() {
        if (claim != null) {
            claim.close();
            claim = null;
        }
    }
}
