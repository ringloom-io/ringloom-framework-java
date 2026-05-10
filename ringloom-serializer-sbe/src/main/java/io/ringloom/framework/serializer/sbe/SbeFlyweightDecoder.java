// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.FlyweightDecoder;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link FlyweightDecoder} adapter for reusable SBE flyweights.
 *
 * @param <T> the flyweight type
 */
public final class SbeFlyweightDecoder<T> implements FlyweightDecoder<T> {
    private final int templateId;
    private final Class<T> flyweightType;
    private final Supplier<? extends T> flyweightFactory;
    private final WrapOperation<T> wrapOperation;
    private final ThreadLocal<SbeDecodeContext> contexts = ThreadLocal.withInitial(SbeDecodeContext::new);

    public SbeFlyweightDecoder(
            int templateId,
            Class<T> flyweightType,
            Supplier<? extends T> flyweightFactory,
            WrapOperation<T> wrapOperation) {
        this.templateId = templateId;
        this.flyweightType = Objects.requireNonNull(flyweightType, "flyweightType");
        this.flyweightFactory = Objects.requireNonNull(flyweightFactory, "flyweightFactory");
        this.wrapOperation = Objects.requireNonNull(wrapOperation, "wrapOperation");
    }

    @Override
    public int templateId() {
        return templateId;
    }

    @Override
    public T wrap(MemorySegment payload, DecodeContext context) {
        SbeDecodeContext sbeContext = contexts.get();
        MemorySegmentDirectBuffer buffer = sbeContext.wrap(payload);
        T flyweight = sbeContext.flyweight(flyweightType, flyweightFactory);
        return wrapOperation.wrap(flyweight, buffer, sbeContext);
    }

    /**
     * Wraps a borrowed payload buffer with a reusable flyweight.
     *
     * @param <T> the flyweight type
     */
    @FunctionalInterface
    public interface WrapOperation<T> {
        T wrap(T flyweight, MemorySegmentDirectBuffer source, SbeDecodeContext context);
    }
}
