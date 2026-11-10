// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv.codec;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.MessageDecoder;
import io.ringloom.framework.serialization.MessageEncoder;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * {@link KvKeyCodec} adapter over a framework {@link MessageEncoder}/{@link MessageDecoder}, used
 * for key types named by {@code keySerializer}.
 *
 * <p>Thread-safe: encode/decode contexts are per thread. Allocation depends on the serializer.
 *
 * @param <K> the key type
 */
public final class SerializerKvKeyCodec<K> implements KvKeyCodec<K> {
    private final MessageEncoder<K> encoder;
    private final MessageDecoder<K> decoder;
    private final ThreadLocal<EncodeContext> encodeContexts = ThreadLocal.withInitial(EncodeContext::new);
    private final ThreadLocal<DecodeContext> decodeContexts = ThreadLocal.withInitial(DecodeContext::new);

    /**
     * @param encoder the key encoder
     * @param decoder the key decoder, or {@code null} for an encode-only codec
     */
    public SerializerKvKeyCodec(MessageEncoder<K> encoder, MessageDecoder<K> decoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.decoder = decoder;
    }

    @Override
    public int encode(K key, MemorySegment target, long capacity) {
        EncodeContext context = encodeContexts.get();
        int length = encoder.encodedLength(key, context);
        if (length < 0 || length > Math.min(capacity, target.byteSize())) {
            return DOES_NOT_FIT;
        }
        return encoder.encode(key, context.buffer().wrap(target, length), context);
    }

    @Override
    public K decode(MemorySegment source, long offset, long length) {
        if (decoder == null) {
            throw new UnsupportedOperationException("key codec has no decoder");
        }
        DecodeContext context = decodeContexts.get();
        MemorySegment payload = offset == 0L ? source : source.asSlice(offset, length);
        return decoder.decode(context.buffer().wrap(payload, length), context);
    }
}
