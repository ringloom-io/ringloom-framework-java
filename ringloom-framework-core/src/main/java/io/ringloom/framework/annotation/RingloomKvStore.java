// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a generated replicated KV store proxy. Methods carry exactly one of
 * {@link RingloomKvGet}, {@link RingloomKvContains}, {@link RingloomKvForEach}, {@link RingloomKvPut},
 * {@link RingloomKvDelete} or {@link RingloomKvClear}; the interface may extend
 * {@code io.ringloom.framework.kv.KvReaderOperations} and/or
 * {@code io.ringloom.framework.kv.KvWriterOperations}. Obtain the generated proxy with
 * {@code runtime.generatedClient(MyStore.class)}.
 *
 * <p><strong>Key encoding (cross-language contract).</strong> Every binding reading or writing the
 * same store must produce identical key bytes:
 *
 * <ul>
 *   <li>{@code long}: 8 bytes big-endian with the sign bit flipped ({@code key ^ Long.MIN_VALUE}),
 *       so byte order equals signed numeric order;
 *   <li>{@code int}: 4 bytes big-endian with the sign bit flipped;
 *   <li>{@code String}: UTF-8 (an unpaired surrogate yields {@code INVALID_ARGUMENT});
 *   <li>{@code MemorySegment} and {@code byte[]}: the bytes as-is;
 *   <li>any other type: encoded by the serializer named by {@link #keySerializer()}.
 * </ul>
 *
 * <p>Iteration order is engine-defined, even though {@code long} keys sort numerically on LMDB
 * today.
 *
 * <p><strong>Flyweight lifetime.</strong> A value returned from a generated {@code get} that is a
 * flyweight points into the calling thread's KV value scratch and is valid only until the next KV
 * read of <em>any</em> store on the same thread.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomKvStore {
    /** Store name: 1..64 bytes of {@code [A-Za-z0-9._-]}. */
    String name();

    /** Default value serializer for the store's methods; {@code ""} uses the runtime default. */
    String serializer() default "";

    /** Key serializer; required only for key types without a built-in encoding. */
    String keySerializer() default "";

    /** Writer config: maximum key length in bytes (1..511); {@code -1} uses the native default. */
    int maxKeyLength() default -1;

    /** Writer config: maximum value length in bytes; {@code -1} uses the native default. */
    long maxValueLength() default -1;

    /** Writer config: default entry TTL in milliseconds; {@code 0} never expires. */
    long defaultTtlMillis() default 0;

    /** Writer config: LMDB map size hint in bytes; {@code -1} uses the native default. */
    long mapSizeHint() default -1;

    /** Writer config: roll scheme of the backing topic; {@code ""} uses the native default. */
    String rollScheme() default "";
}
