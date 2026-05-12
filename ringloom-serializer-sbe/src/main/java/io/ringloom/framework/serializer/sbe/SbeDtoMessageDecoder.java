// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.MessageDecoder;
import io.ringloom.framework.serialization.ReadableMessageBuffer;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link MessageDecoder} adapter that decodes borrowed SBE payloads into reusable DTO instances.
 *
 * @param <D> the generated SBE decoder flyweight type
 * @param <T> the DTO type populated from the flyweight
 */
public final class SbeDtoMessageDecoder<D, T> implements MessageDecoder<T> {
    private final int templateId;
    private final Class<D> decoderType;
    private final Supplier<? extends D> decoderFactory;
    private final Class<T> dtoType;
    private final Supplier<? extends T> dtoFactory;
    private final WrapOperation<D> wrapOperation;
    private final DecodeOperation<D, T> decodeOperation;
    private final ThreadLocal<SbeDecodeContext> contexts = ThreadLocal.withInitial(SbeDecodeContext::new);

    public SbeDtoMessageDecoder(
            int templateId,
            Class<D> decoderType,
            Supplier<? extends D> decoderFactory,
            Class<T> dtoType,
            Supplier<? extends T> dtoFactory,
            WrapOperation<D> wrapOperation,
            DecodeOperation<D, T> decodeOperation) {
        this.templateId = templateId;
        this.decoderType = Objects.requireNonNull(decoderType, "decoderType");
        this.decoderFactory = Objects.requireNonNull(decoderFactory, "decoderFactory");
        this.dtoType = Objects.requireNonNull(dtoType, "dtoType");
        this.dtoFactory = Objects.requireNonNull(dtoFactory, "dtoFactory");
        this.wrapOperation = Objects.requireNonNull(wrapOperation, "wrapOperation");
        this.decodeOperation = Objects.requireNonNull(decodeOperation, "decodeOperation");
    }

    @Override
    public int templateId() {
        return templateId;
    }

    @Override
    public T decode(ReadableMessageBuffer source, DecodeContext context) {
        SbeDecodeContext sbeContext = contexts.get();
        MemorySegmentDirectBuffer buffer = sbeContext.wrap(source.segment());
        D decoder = sbeContext.flyweight(decoderType, decoderFactory);
        decoder = wrapOperation.wrap(decoder, buffer, sbeContext);
        T dto = sbeContext.flyweight(dtoType, dtoFactory);
        decodeOperation.decode(decoder, dto);
        return dto;
    }

    @FunctionalInterface
    public interface WrapOperation<D> {
        D wrap(D decoder, MemorySegmentDirectBuffer source, SbeDecodeContext context);
    }

    @FunctionalInterface
    public interface DecodeOperation<D, T> {
        void decode(D decoder, T dto);
    }
}
