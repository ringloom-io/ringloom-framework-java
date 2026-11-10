// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.service.KvStoreConfig;
import java.util.concurrent.CompletableFuture;

/** An open request queued for execution on the control thread (§6.6). */
sealed interface KvOpenCommand {
    String store();

    KvStoreRuntimeConfig settings();

    void fail(Throwable cause);

    /** Opens (or returns) a reader store. */
    record Reader(String store, KvStoreRuntimeConfig settings, CompletableFuture<KvReaderStore> future)
            implements KvOpenCommand {
        @Override
        public void fail(Throwable cause) {
            future.completeExceptionally(cause);
        }
    }

    /** Opens (or returns) a writer store. */
    record Writer(
            String store, KvStoreConfig config, KvStoreRuntimeConfig settings, CompletableFuture<KvWriterStore> future)
            implements KvOpenCommand {
        @Override
        public void fail(Throwable cause) {
            future.completeExceptionally(cause);
        }
    }
}
