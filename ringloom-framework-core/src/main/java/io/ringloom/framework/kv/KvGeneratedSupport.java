// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.kv.codec.KvKeyCodecs;
import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.FlyweightDecoder;
import io.ringloom.framework.serialization.MessageDecoder;
import io.ringloom.framework.serialization.MessageEncoder;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.service.KvStoreConfig;
import io.ringloom.service.KvVisitor;
import io.ringloom.service.RingloomStatus;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Static helpers called by generated {@code @RingloomKvStore} proxies. Not intended for application
 * code. Codecs are resolved once, in the proxy constructor, through {@link #requireEncoder},
 * {@link #flyweight} and {@link #requireDecoder}; the per-call helpers allocate nothing in steady
 * state (except {@link #forEach}, which allocates one adapter per call).
 */
public final class KvGeneratedSupport {
    /** Big-endian unaligned {@code long} layout used for inline {@code long} key encoding. */
    public static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Big-endian unaligned {@code int} layout used for inline {@code int} key encoding. */
    public static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Largest key accepted before the native call (LMDB limit, native §4.2). */
    public static final int MAX_KEY_LENGTH = 511;

    private KvGeneratedSupport() {}

    // ---- construction-time codec resolution ------------------------------------------------------

    /**
     * Resolves an encoder or fails runtime startup.
     *
     * @param serializers the registry
     * @param serializer  the resolved serializer name
     * @param type        the encoded type
     * @param store       the store name (for the message)
     * @param <T>         the type
     * @return the encoder
     * @throws IllegalStateException when no encoder is registered
     */
    public static <T> MessageEncoder<T> requireEncoder(
            SerializerRegistry serializers, String serializer, Class<T> type, String store) {
        MessageEncoder<T> encoder = serializers.encoder(serializer, type);
        if (encoder == null) {
            throw new IllegalStateException(
                    "kv store '" + store + "': no '" + serializer + "' encoder registered for " + type.getName());
        }
        return encoder;
    }

    /**
     * Returns the flyweight decoder for a value type, or {@code null} when none is registered.
     *
     * @param serializers the registry
     * @param serializer  the resolved serializer name
     * @param type        the value type
     * @param <T>         the type
     * @return the flyweight decoder or {@code null}
     */
    public static <T> FlyweightDecoder<T> flyweight(SerializerRegistry serializers, String serializer, Class<T> type) {
        return serializers.flyweight(serializer, type);
    }

    /**
     * Resolves a copying decoder for a value type unless a flyweight is available.
     *
     * @param serializers the registry
     * @param serializer  the resolved serializer name
     * @param type        the value type
     * @param flyweight   the flyweight resolved for the same type, or {@code null}
     * @param store       the store name (for the message)
     * @param <T>         the type
     * @return the decoder, or {@code null} when {@code flyweight} is non-null
     * @throws IllegalStateException when neither a flyweight nor a decoder is registered
     */
    public static <T> MessageDecoder<T> requireDecoder(
            SerializerRegistry serializers,
            String serializer,
            Class<T> type,
            FlyweightDecoder<T> flyweight,
            String store) {
        if (flyweight != null) {
            return null;
        }
        MessageDecoder<T> decoder = serializers.decoder(serializer, type);
        if (decoder == null) {
            throw new IllegalStateException("kv store '" + store + "': no '" + serializer
                    + "' decoder or flyweight registered for " + type.getName());
        }
        return decoder;
    }

    // ---- hot-path helpers ------------------------------------------------------------------------

    /**
     * Maps a negative key-encoding result to a status: {@code KvKeyCodecs.DOES_NOT_FIT} ({@code -1})
     * to {@code KV_KEY_TOO_LARGE}, {@code KvKeyCodecs.UNPAIRED_SURROGATE} ({@code -2}) to
     * {@code INVALID_ARGUMENT}, and a negated status (from {@link #encodeKey}) to that status.
     *
     * @param encodeResult the negative encode result
     * @return the status to return to the caller
     */
    public static int keyStatus(long encodeResult) {
        if (encodeResult == KvKeyCodecs.DOES_NOT_FIT) {
            return RingloomStatus.KV_KEY_TOO_LARGE;
        }
        if (encodeResult == KvKeyCodecs.UNPAIRED_SURROGATE) {
            return RingloomStatus.INVALID_ARGUMENT;
        }
        return (int) -encodeResult;
    }

    /**
     * Encodes a key with a serializer into the key scratch.
     *
     * @return the key length, or a negated status ({@code KV_KEY_TOO_LARGE}, {@code SERIALIZATION_ERROR})
     */
    public static <K> int encodeKey(MessageEncoder<K> encoder, K key, KvThreadState state, int maxKeyLength) {
        EncodeContext context = state.encodeContext();
        try {
            int length = encoder.encodedLength(key, context);
            if (length > maxKeyLength || length > KvThreadState.KEY_SCRATCH_BYTES) {
                return -RingloomStatus.KV_KEY_TOO_LARGE;
            }
            context.buffer().wrap(state.keyScratch(), length);
            return encoder.encode(key, context.buffer(), context);
        } catch (RuntimeException ex) {
            return -RingloomHandlerStatus.SERIALIZATION_ERROR;
        }
    }

    /**
     * Encodes a value into the value scratch (growing it when needed).
     *
     * @return the value length, or a negated status ({@code KV_VALUE_TOO_LARGE}, {@code SERIALIZATION_ERROR})
     */
    public static <V> int encodeValue(MessageEncoder<V> encoder, V value, KvThreadState state, KvWriterStore writer) {
        EncodeContext context = state.encodeContext();
        try {
            int length = encoder.encodedLength(value, context);
            KvStoreConfig config = writer.config();
            if (length < 0 || (config != null && length > config.maxValueLength())) {
                return -RingloomStatus.KV_VALUE_TOO_LARGE;
            }
            context.buffer().wrap(state.valueScratch(length), length);
            return encoder.encode(value, context.buffer(), context);
        } catch (RuntimeException ex) {
            return -RingloomHandlerStatus.SERIALIZATION_ERROR;
        }
    }

    /**
     * Copying get of {@code key[0, keyLength)} into the thread's value scratch. A
     * {@code KV_BUFFER_TOO_SMALL} result (a store whose config was unknown when the scratch was sized)
     * grows the scratch once and retries. On {@code OK} the value is in
     * {@link KvThreadState#valueScratch()} and its length in {@code state.outLength()[0]}.
     *
     * @return the reader status
     */
    public static int read(KvReaderStore reader, KvThreadState state, MemorySegment key, long keyLength) {
        MemorySegment out = state.valueScratch();
        long[] outLength = state.outLength();
        int status = reader.get(key, 0, keyLength, out, 0, out.byteSize(), outLength);
        if (status == RingloomStatus.KV_BUFFER_TOO_SMALL) {
            out = state.valueScratch(outLength[0]);
            status = reader.get(key, 0, keyLength, out, 0, out.byteSize(), outLength);
        }
        return status;
    }

    /**
     * Decodes {@code source[0, length)} with the flyweight when present, otherwise with the decoder.
     *
     * @throws RuntimeException when decoding fails
     */
    public static <V> V decode(
            FlyweightDecoder<V> flyweight,
            MessageDecoder<V> decoder,
            KvThreadState state,
            MemorySegment source,
            long length) {
        DecodeContext context = state.decodeContext();
        context.payloadLength(length);
        if (flyweight != null) {
            return flyweight.wrap(source, context);
        }
        return decoder.decode(context.buffer().wrap(source, length), context);
    }

    /**
     * Iterates one snapshot, decoding each value. Allocates one adapter per call.
     *
     * @return {@code OK}, {@code SERIALIZATION_ERROR} when a value could not be decoded (iteration
     *     stops), or the reader status
     */
    public static <V> int forEach(
            KvRuntime kv,
            KvReaderStore reader,
            KvThreadState state,
            FlyweightDecoder<V> flyweight,
            MessageDecoder<V> decoder,
            KvEntryVisitor<V> visitor) {
        ForEachAdapter<V> adapter = new ForEachAdapter<>(state, flyweight, decoder, visitor);
        int status = reader.forEach(adapter);
        if (adapter.failure != null) {
            kv.recordDecodeError(reader, adapter.failure);
            return RingloomHandlerStatus.SERIALIZATION_ERROR;
        }
        return status;
    }

    private static final class ForEachAdapter<V> implements KvVisitor {
        private final KvThreadState state;
        private final FlyweightDecoder<V> flyweight;
        private final MessageDecoder<V> decoder;
        private final KvEntryVisitor<V> visitor;
        private RuntimeException failure;

        ForEachAdapter(
                KvThreadState state,
                FlyweightDecoder<V> flyweight,
                MessageDecoder<V> decoder,
                KvEntryVisitor<V> visitor) {
            this.state = state;
            this.flyweight = flyweight;
            this.decoder = decoder;
            this.visitor = visitor;
        }

        @Override
        public boolean visit(MemorySegment key, MemorySegment value) {
            V decoded;
            try {
                decoded = decode(flyweight, decoder, state, value, value.byteSize());
            } catch (RuntimeException ex) {
                failure = ex;
                return false;
            }
            return visitor.visit(key, decoded);
        }
    }
}
