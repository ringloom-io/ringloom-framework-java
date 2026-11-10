// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Model for a {@code @RingloomKvStore}-annotated interface.
 *
 * @param storeInterface      the annotated interface
 * @param name                the store name
 * @param keyKind             the key encoding, or {@code null} when no method takes a key
 * @param keyType             the key type source name, or {@code null}
 * @param keySerializer       the key serializer ({@code ""} for built-in keys)
 * @param readerOperations    whether the interface extends {@code KvReaderOperations}
 * @param writerOperations    whether the interface extends {@code KvWriterOperations}
 * @param maxKeyLength        annotation {@code maxKeyLength} ({@code -1} = native default)
 * @param maxValueLength      annotation {@code maxValueLength} ({@code -1} = native default)
 * @param defaultTtlMillis    annotation {@code defaultTtlMillis} (negative = native default)
 * @param mapSizeHint         annotation {@code mapSizeHint} ({@code -1} = native default)
 * @param rollScheme          annotation {@code rollScheme} ({@code ""} = native default)
 * @param methods             the KV methods, in declaration order
 */
public record KvStore(
        TypeElement storeInterface,
        String name,
        KeyKind keyKind,
        String keyType,
        String keySerializer,
        boolean readerOperations,
        boolean writerOperations,
        int maxKeyLength,
        long maxValueLength,
        long defaultTtlMillis,
        long mapSizeHint,
        String rollScheme,
        List<KvMethod> methods) {

    /** Key encodings (§8.3). */
    public enum KeyKind {
        LONG,
        INT,
        STRING,
        SEGMENT,
        BYTES,
        SERIALIZER
    }

    /**
     * Returns whether the store needs a reader handle.
     *
     * @return {@code true} when a read method exists or the interface extends {@code KvReaderOperations}
     */
    public boolean reads() {
        return readerOperations || methods.stream().anyMatch(m -> !m.shape().write());
    }

    /**
     * Returns whether the store needs a writer handle.
     *
     * @return {@code true} when a write method exists or the interface extends {@code KvWriterOperations}
     */
    public boolean writes() {
        return writerOperations || methods.stream().anyMatch(m -> m.shape().write());
    }

    /**
     * Returns the key length hint for scratch sizing.
     *
     * @return the key length hint in bytes
     */
    public int keyLengthHint() {
        if (keyKind == KeyKind.LONG) {
            return Long.BYTES;
        }
        if (keyKind == KeyKind.INT) {
            return Integer.BYTES;
        }
        return effectiveMaxKeyLength();
    }

    /**
     * Returns the key length limit checked before native calls.
     *
     * @return {@code maxKeyLength} when set, otherwise the LMDB limit (511)
     */
    public int effectiveMaxKeyLength() {
        return maxKeyLength > 0 ? maxKeyLength : 511;
    }

    /**
     * Returns the generated proxy simple name.
     *
     * @return {@code <Interface>_RingloomKvStore}, with enclosing type names prefixed for nested interfaces
     */
    public String generatedSimpleName() {
        StringBuilder name = new StringBuilder(storeInterface.getSimpleName());
        javax.lang.model.element.Element enclosing = storeInterface.getEnclosingElement();
        while (enclosing instanceof TypeElement outer) {
            name.insert(0, outer.getSimpleName() + "_");
            enclosing = outer.getEnclosingElement();
        }
        return name + "_RingloomKvStore";
    }
}
