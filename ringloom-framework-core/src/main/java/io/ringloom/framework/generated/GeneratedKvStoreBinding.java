// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.service.KvStoreConfig;

/**
 * Generated binding for one {@code @RingloomKvStore} interface. The runtime declares the store's
 * reader and/or writer before the KV runtime opens its stores, then calls {@link #create} and
 * registers the proxy under {@link #storeType()} so {@code runtime.generatedClient(type)} returns it.
 * Several bindings may name the same store; they share one reader and one writer handle.
 */
public interface GeneratedKvStoreBinding {
    /**
     * Returns the annotated store interface.
     *
     * @return the store interface type
     */
    Class<?> storeType();

    /**
     * Returns the store name.
     *
     * @return the store name
     */
    String store();

    /**
     * Returns whether the interface declares read methods (or extends {@code KvReaderOperations}).
     *
     * @return {@code true} when a reader is needed
     */
    boolean reads();

    /**
     * Returns whether the interface declares write methods (or extends {@code KvWriterOperations}).
     *
     * @return {@code true} when a writer is needed
     */
    boolean writes();

    /**
     * Returns the writer store config: annotation values applied over {@link KvStoreConfig#defaults()}.
     *
     * @return the writer config, or {@code null} when {@link #writes()} is false
     */
    KvStoreConfig storeConfig();

    /**
     * Returns the key length hint from the key type and {@code maxKeyLength}, for scratch sizing.
     *
     * @return the key length hint in bytes
     */
    int keyLengthHint();

    /**
     * Creates the store proxy. Called after the KV runtime opened its stores; resolves every codec
     * once and fails with {@link IllegalStateException} when one is missing.
     *
     * @param runtime     the runtime
     * @param serializers the serializer registry
     * @return the proxy implementing {@link #storeType()}
     */
    Object create(RingloomRuntime runtime, SerializerRegistry serializers);
}
