// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.MessageDecoder;
import io.ringloom.framework.serialization.MessageEncoder;
import io.ringloom.framework.serialization.ReadableMessageBuffer;
import io.ringloom.framework.serialization.RingloomSerializationException;
import io.ringloom.framework.serialization.WritableMessageBuffer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Apache Fory-backed message encoder and decoder.
 *
 * @param <T> the decoded value type
 */
public final class ForyMessageCodec<T> implements MessageEncoder<T>, MessageDecoder<T> {
    public static final int NON_TEMPLATE_SPECIFIC = -1;

    private final ThreadSafeFory fory;
    private final Class<T> decodedType;
    private final int maxPayloadBytes;
    private final ThreadLocal<ForyEncodeContext> encodeContexts;

    public ForyMessageCodec(ThreadSafeFory fory, Class<T> decodedType, int maxPayloadBytes) {
        this.fory = Objects.requireNonNull(fory, "fory");
        this.decodedType = Objects.requireNonNull(decodedType, "decodedType");
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.encodeContexts = ThreadLocal.withInitial(() -> new ForyEncodeContext(maxPayloadBytes));
    }

    @Override
    public int templateId() {
        return NON_TEMPLATE_SPECIFIC;
    }

    @Override
    public int encodedLength(T value, EncodeContext context) {
        ForyEncodeContext foryContext = encodeContexts.get();
        serialize(value, foryContext);
        return foryContext.length();
    }

    @Override
    public int encode(T value, WritableMessageBuffer target, EncodeContext context) {
        ForyEncodeContext foryContext = encodeContexts.get();
        if (!foryContext.matches(value)) {
            serialize(value, foryContext);
        }
        if (foryContext.length() > target.length()) {
            throw new RingloomSerializationException(
                    "Fory payload length " + foryContext.length() + " exceeds target length " + target.length());
        }
        MemorySegment.copy(
                foryContext.heapMemory(), 0, target.segment(), ValueLayout.JAVA_BYTE, 0, foryContext.length());
        return foryContext.length();
    }

    @Override
    public T decode(ReadableMessageBuffer source, DecodeContext context) {
        try {
            MemorySegment segment = source.segment();
            long length = source.length();
            if (length > maxPayloadBytes) {
                throw new RingloomSerializationException(
                        "Fory payload length " + length + " exceeds maxPayloadBytes " + maxPayloadBytes);
            }
            Object value = segment.isNative()
                    ? fory.deserialize(segment.address(), Math.toIntExact(length))
                    : fory.deserialize(segment.toArray(ValueLayout.JAVA_BYTE));
            return decodedType.cast(value);
        } catch (RingloomSerializationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RingloomSerializationException("failed to decode Fory payload", ex);
        }
    }

    private void serialize(T value, ForyEncodeContext context) {
        context.reset();
        try {
            MemoryBuffer buffer = fory.execute(runtime -> runtime.serialize(context.buffer(), value));
            int length = buffer.writerIndex();
            if (length > maxPayloadBytes) {
                throw new RingloomSerializationException(
                        "Fory payload length " + length + " exceeds maxPayloadBytes " + maxPayloadBytes);
            }
            context.prepare(value, buffer);
        } catch (RingloomSerializationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RingloomSerializationException("failed to encode Fory payload", ex);
        }
    }
}
