// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.MessageEncoder;
import io.ringloom.framework.serialization.WritableMessageBuffer;
import java.util.Objects;

/**
 * {@link MessageEncoder} adapter for SBE codecs that encode into caller-owned native memory.
 *
 * @param <T> the application value type encoded by this adapter
 */
public final class SbeMessageEncoder<T> implements MessageEncoder<T> {
    private final int templateId;
    private final EncodedLength<T> encodedLength;
    private final EncodeOperation<T> encodeOperation;
    private final ThreadLocal<SbeEncodeContext> contexts = ThreadLocal.withInitial(SbeEncodeContext::new);

    public SbeMessageEncoder(int templateId, EncodedLength<T> encodedLength, EncodeOperation<T> encodeOperation) {
        this.templateId = templateId;
        this.encodedLength = Objects.requireNonNull(encodedLength, "encodedLength");
        this.encodeOperation = Objects.requireNonNull(encodeOperation, "encodeOperation");
    }

    @Override
    public int templateId() {
        return templateId;
    }

    @Override
    public int encodedLength(T value, EncodeContext context) {
        return encodedLength.encodedLength(value, contexts.get());
    }

    @Override
    public int encode(T value, WritableMessageBuffer target, EncodeContext context) {
        SbeEncodeContext sbeContext = contexts.get();
        MemorySegmentMutableDirectBuffer buffer = sbeContext.wrap(target.segment());
        return encodeOperation.encode(value, buffer, sbeContext);
    }

    /**
     * Computes the encoded SBE payload length before a buffer claim is acquired.
     *
     * @param <T> the value type
     */
    @FunctionalInterface
    public interface EncodedLength<T> {
        int encodedLength(T value, SbeEncodeContext context);
    }

    /**
     * Encodes a value into an already wrapped native target buffer.
     *
     * @param <T> the value type
     */
    @FunctionalInterface
    public interface EncodeOperation<T> {
        int encode(T value, MemorySegmentMutableDirectBuffer target, SbeEncodeContext context);
    }
}
