// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import java.util.function.Supplier;

/**
 * Factory helpers for SBE serializer adapters used by generated or hand-written wiring.
 */
public final class SbeCodecFactory {
    private SbeCodecFactory() {}

    /**
     * Creates a zero-copy SBE message encoder adapter.
     *
     * @param templateId the template id written by the generated client
     * @param encodedLength length calculation callback
     * @param encodeOperation direct-memory encode callback
     * @param <T> the application value type
     * @return the message encoder
     */
    public static <T> SbeMessageEncoder<T> encoder(
            int templateId,
            SbeMessageEncoder.EncodedLength<T> encodedLength,
            SbeMessageEncoder.EncodeOperation<T> encodeOperation) {
        return new SbeMessageEncoder<>(templateId, encodedLength, encodeOperation);
    }

    /**
     * Creates a reusable SBE flyweight decoder adapter.
     *
     * @param templateId the template id accepted by the generated dispatcher
     * @param flyweightType the flyweight type
     * @param flyweightFactory factory used once per context
     * @param wrapOperation callback that wraps the borrowed payload
     * @param <T> the flyweight type
     * @return the flyweight decoder
     */
    public static <T> SbeFlyweightDecoder<T> flyweight(
            int templateId,
            Class<T> flyweightType,
            Supplier<? extends T> flyweightFactory,
            SbeFlyweightDecoder.WrapOperation<T> wrapOperation) {
        return new SbeFlyweightDecoder<>(templateId, flyweightType, flyweightFactory, wrapOperation);
    }
}
