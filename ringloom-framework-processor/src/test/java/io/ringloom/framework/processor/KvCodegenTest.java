// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.generated.GeneratedKvStoreBinding;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.kv.KvWriterState;
import io.ringloom.framework.kv.KvWriterStateEvent;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.SimpleJavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class KvCodegenTest {
    private static final String GOLDEN = "/io/ringloom/framework/processor/kv/PriceStore_RingloomKvStore.java.golden";

    private static final String APP_SRC = """
            package test;
            import io.ringloom.framework.annotation.RingloomApplication;
            @RingloomApplication(service = "kv-app")
            public final class KvApp {}
            """;

    private static final String QUOTE_SRC = """
            package test;
            public record Quote(String symbol, long price) {}
            """;

    private static final String ORDER_DTO_SRC = """
            package test;
            public final class OrderDto {
              public int computeEncodedLength() { return 8; }
              public static void encodeWith(OrderEncoder encoder, OrderDto dto) {}
              public static void decodeWith(OrderDecoder decoder, OrderDto dto) {}
            }
            """;

    private static final String ORDER_ENCODER_SRC = """
            package test;
            import org.agrona.MutableDirectBuffer;
            public final class OrderEncoder {
              public static final int BLOCK_LENGTH = 8;
              public OrderEncoder wrap(MutableDirectBuffer buffer, int offset) { return this; }
              public int encodedLength() { return 8; }
            }
            """;

    private static final String ORDER_DECODER_SRC = """
            package test;
            import org.agrona.DirectBuffer;
            public final class OrderDecoder {
              public static final int BLOCK_LENGTH = 8;
              public static final int SCHEMA_VERSION = 1;
              public OrderDecoder wrap(DirectBuffer buffer, int offset, int blockLength, int version) { return this; }
            }
            """;

    /** One interface covering every §9.2 method shape (long key, SBE values). */
    private static final String PRICE_STORE_SRC = """
            package test;
            import io.ringloom.framework.annotation.RingloomKvClear;
            import io.ringloom.framework.annotation.RingloomKvContains;
            import io.ringloom.framework.annotation.RingloomKvDelete;
            import io.ringloom.framework.annotation.RingloomKvForEach;
            import io.ringloom.framework.annotation.RingloomKvGet;
            import io.ringloom.framework.annotation.RingloomKvPut;
            import io.ringloom.framework.annotation.RingloomKvStore;
            import io.ringloom.framework.annotation.RingloomKvTtl;
            import io.ringloom.framework.kv.KvEntryVisitor;
            import io.ringloom.framework.kv.KvReaderOperations;
            import io.ringloom.framework.kv.KvValue;
            import io.ringloom.framework.kv.KvWriterOperations;
            import io.ringloom.framework.topic.ack.AckCallback;
            import io.ringloom.service.KvRawVisitor;
            import io.ringloom.service.TopicAckMode;
            import java.lang.foreign.MemorySegment;
            @RingloomKvStore(name = "prices", serializer = "sbe", maxKeyLength = 8, maxValueLength = 256)
            public interface PriceStore extends KvReaderOperations, KvWriterOperations {
              @RingloomKvGet OrderDecoder get(long id);
              @RingloomKvGet int get(long id, KvValue<OrderDecoder> out);
              @RingloomKvGet OrderDto getDto(long id);
              @RingloomKvGet int getRaw(long id, MemorySegment destination, long[] outLength);
              @RingloomKvContains boolean contains(long id);
              @RingloomKvForEach int forEach(KvEntryVisitor<OrderDecoder> visitor);
              @RingloomKvForEach int forEachRaw(KvRawVisitor visitor);
              @RingloomKvPut int put(long id, OrderDto order);
              @RingloomKvPut int putRaw(long id, MemorySegment order);
              @RingloomKvPut(ttlMillis = 0) int putForever(long id, OrderDto order);
              @RingloomKvPut int putExpiring(long id, OrderDto order, @RingloomKvTtl long ttlMillis);
              @RingloomKvPut(ackMode = TopicAckMode.REPLICATE_ONCE)
              int putAcked(long id, OrderDto order, AckCallback callback, Object context);
              @RingloomKvPut(ackMode = TopicAckMode.REPLICATE_ONCE)
              int putExpiringAcked(long id, OrderDto order, @RingloomKvTtl long ttlMillis, AckCallback callback, Object context);
              @RingloomKvDelete int delete(long id);
              @RingloomKvDelete(ackMode = TopicAckMode.REPLICATE_ONCE)
              int deleteAcked(long id, AckCallback callback, Object context);
              @RingloomKvClear int clear();
              @RingloomKvClear(ackMode = TopicAckMode.REPLICATE_ONCE)
              int clearAcked(AckCallback callback, Object context);
              default boolean ignoredDefault() { return true; }
              static int ignoredStatic() { return 0; }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void generatesGoldenProxyCoveringEveryMethodShape() throws Exception {
        Compiled compiled = compile(
                "golden",
                source("test.KvApp", APP_SRC),
                source("test.OrderDto", ORDER_DTO_SRC),
                source("test.OrderEncoder", ORDER_ENCODER_SRC),
                source("test.OrderDecoder", ORDER_DECODER_SRC),
                source("test.PriceStore", PRICE_STORE_SRC));

        assertThat(compiled.result.success()).as("compilation should succeed").isTrue();
        String proxy = compiled.generatedSource("test/PriceStore_RingloomKvStore.java");
        assertThat(proxy).isEqualTo(golden());
        // No reflection and no per-call serializer lookup: codecs are resolved in the constructor only.
        assertThat(proxy)
                .doesNotContain("java.lang.reflect")
                .doesNotContain("serializers.encoder(")
                .doesNotContain("serializers.decoder(")
                .doesNotContain("serializers.flyweight(")
                .doesNotContain("Long.valueOf");

        String app = compiled.generatedSource("test/KvApp_RingloomApplication.java");
        assertThat(app)
                .contains("public List<GeneratedKvStoreBinding> kvStores()")
                .contains("return test.PriceStore.class;")
                .contains("return \"prices\";")
                .contains("defaults.formatVersion(),\n                8,\n                256L,\n                0L,")
                .contains("defaults.mapSizeHint(),\n                defaults.topicRollScheme(),")
                .contains("return new test.PriceStore_RingloomKvStore(runtime, serializers);")
                // SBE codecs for KV values are registered with template id 0.
                .contains("builder.encoder(\n                \"sbe\",\n                test.OrderDto.class")
                .contains("builder.flyweight(\n                \"sbe\",\n                test.OrderDecoder.class")
                .contains("builder.decoder(\n                \"sbe\",\n                test.OrderDto.class")
                .doesNotContain("dispatchKvWriterState");
    }

    @Test
    void encodesEveryBuiltInKeyTypeInlineAndResolvesKeySerializer() throws Exception {
        Compiled compiled = compile(
                "keys",
                source("test.KvApp", APP_SRC),
                source("test.Quote", QUOTE_SRC),
                source("test.AccountKey", """
                        package test;
                        public record AccountKey(String region, long id) {}
                        """),
                source("test.Stores", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvValue;
                        import java.lang.foreign.MemorySegment;
                        public interface Stores {
                          @RingloomKvStore(name = "quotes", serializer = "fory")
                          interface QuoteStore {
                            @RingloomKvGet Quote get(String symbol);
                            @RingloomKvGet int get(String symbol, KvValue<Quote> out);
                            @RingloomKvContains boolean contains(String symbol);
                            @RingloomKvPut int put(String symbol, Quote quote);
                          }
                          @RingloomKvStore(name = "counters", serializer = "fory")
                          interface CounterStore {
                            @RingloomKvContains boolean contains(int id);
                            @RingloomKvDelete int delete(int id);
                          }
                          @RingloomKvStore(name = "blobs")
                          interface BlobStore {
                            @RingloomKvGet int get(MemorySegment key, MemorySegment destination, long[] outLength);
                            @RingloomKvContains boolean contains(MemorySegment key);
                            @RingloomKvPut int put(MemorySegment key, MemorySegment value);
                          }
                          @RingloomKvStore(name = "bytes")
                          interface BytesStore {
                            @RingloomKvDelete int delete(byte[] key);
                          }
                          @RingloomKvStore(name = "accounts", serializer = "fory", keySerializer = "fory")
                          interface AccountStore {
                            @RingloomKvGet Quote get(AccountKey key);
                            @RingloomKvPut int put(AccountKey key, Quote quote);
                          }
                        }
                        """));

        assertThat(compiled.result.success()).as("compilation should succeed").isTrue();
        String quotes = compiled.generatedSource("test/Stores_QuoteStore_RingloomKvStore.java");
        assertThat(quotes)
                .contains("long $keyLength = KvKeyCodecs.encodeUtf8(symbol, $keySegment, MAX_KEY_LENGTH);")
                .contains("return out.fail(KvGeneratedSupport.keyStatus($keyLength)).status();")
                .contains("private static final int MAX_KEY_LENGTH = 511;")
                .contains("runtime.resolveSerializerName(\"fory\")");
        assertThat(compiled.generatedSource("test/Stores_CounterStore_RingloomKvStore.java"))
                .contains("$keySegment.set(KvGeneratedSupport.BE_INT, 0L, id ^ Integer.MIN_VALUE);")
                .contains("long $keyLength = 4L;")
                .doesNotContain("KvReaderOperations");
        String blobs = compiled.generatedSource("test/Stores_BlobStore_RingloomKvStore.java");
        assertThat(blobs)
                .contains("MemorySegment $keySegment = key;")
                .contains("return RingloomStatus.KV_KEY_TOO_LARGE;")
                .contains("return false;")
                .contains("this.writer.put($keySegment, 0L, $keyLength, value, 0L, value.byteSize(), -1L)")
                .doesNotContain("kv.threadState()");
        assertThat(compiled.generatedSource("test/Stores_BytesStore_RingloomKvStore.java"))
                .contains("KvKeyCodecs.encodeBytes(key, $keySegment, MAX_KEY_LENGTH)");
        assertThat(compiled.generatedSource("test/Stores_AccountStore_RingloomKvStore.java"))
                .contains("private final MessageEncoder<test.AccountKey> keyEncoder;")
                .contains("runtime.resolveSerializerName(\"fory\"), test.AccountKey.class, STORE);")
                .contains("KvGeneratedSupport.encodeKey(this.keyEncoder, key, $state, MAX_KEY_LENGTH)");
        String app = compiled.generatedSource("test/KvApp_RingloomApplication.java");
        assertThat(app).contains("foryTypes.add(test.Quote.class);").contains("foryTypes.add(test.AccountKey.class);");
    }

    @Test
    void parameterNamesMatchingGeneratedLocalsAndFieldsCompile() throws Exception {
        // Every parameter is named after a generated local or proxy field; the proxy must neither
        // redeclare a variable nor let a parameter shadow a field.
        Compiled compiled = compile(
                "clashing-names",
                source("test.KvApp", APP_SRC),
                source("test.Quote", QUOTE_SRC),
                source("test.AccountKey", """
                        package test;
                        public record AccountKey(String region, long id) {}
                        """),
                source("test.Stores", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvEntryVisitor;
                        import io.ringloom.framework.kv.KvValue;
                        import io.ringloom.framework.topic.ack.AckCallback;
                        import io.ringloom.service.KvRawVisitor;
                        import io.ringloom.service.TopicAckMode;
                        import java.lang.foreign.MemorySegment;
                        public interface Stores {
                          @RingloomKvStore(name = "quotes", serializer = "fory")
                          interface QuoteStore {
                            @RingloomKvGet Quote get(String state);
                            @RingloomKvGet int get(String keySegment, KvValue<Quote> status);
                            @RingloomKvGet int getRaw(String keyLength, MemorySegment length, long[] ex);
                            @RingloomKvContains boolean contains(String state);
                            @RingloomKvForEach int forEach(KvEntryVisitor<Quote> reader);
                            @RingloomKvForEach int forEachRaw(KvRawVisitor kv);
                            @RingloomKvPut int put(String valueLength, Quote writer);
                            @RingloomKvPut int putExpiring(String state, Quote c0Encoder, @RingloomKvTtl long keyLength);
                            @RingloomKvPut(ackMode = TopicAckMode.REPLICATE_ONCE)
                            int putAcked(String keySegment, Quote valueLength, AckCallback writer, Object state);
                            @RingloomKvDelete(ackMode = TopicAckMode.REPLICATE_ONCE)
                            int delete(String keyLength, AckCallback kv, Object writer);
                            @RingloomKvClear(ackMode = TopicAckMode.REPLICATE_ONCE)
                            int clear(AckCallback writer, Object kv);
                          }
                          @RingloomKvStore(name = "blobs")
                          interface BlobStore {
                            @RingloomKvContains boolean contains(MemorySegment keyLength);
                            @RingloomKvPut int put(MemorySegment state, MemorySegment keySegment);
                          }
                          @RingloomKvStore(name = "accounts", serializer = "fory", keySerializer = "fory")
                          interface AccountStore {
                            @RingloomKvGet Quote get(AccountKey keyEncoder);
                            @RingloomKvPut int put(AccountKey keyEncoder, Quote state);
                          }
                        }
                        """));

        assertThat(compiled.result.errors()).isEmpty();
        assertThat(compiled.result.success()).as("compilation should succeed").isTrue();
    }

    @Test
    void mergedStoreProducesOneBindingPerInterfaceWithReaderAndWriterRoles() throws Exception {
        Compiled compiled = compile(
                "merged",
                source("test.KvApp", APP_SRC),
                source("test.Quote", QUOTE_SRC),
                source("test.QuoteReader", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "quotes", serializer = "fory")
                        public interface QuoteReader {
                          @RingloomKvGet Quote get(long id);
                        }
                        """),
                source("test.QuoteWriter", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "quotes", serializer = "fory", maxValueLength = 1024)
                        public interface QuoteWriter {
                          @RingloomKvPut int put(long id, Quote quote);
                        }
                        """),
                source("test.QuoteWriterOps", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvWriterOperations;
                        @RingloomKvStore(name = "quotes", maxValueLength = 1024)
                        public interface QuoteWriterOps extends KvWriterOperations {}
                        """));

        assertThat(compiled.result.success()).as("compilation should succeed").isTrue();
        try (URLClassLoader loader = compiled.loader()) {
            GeneratedRingloomApplication app = compiled.application(loader);
            assertThat(app.requiresKvStores()).isTrue();
            List<GeneratedKvStoreBinding> bindings = app.kvStores();
            assertThat(bindings).hasSize(3);
            assertThat(bindings)
                    .allSatisfy(binding -> assertThat(binding.store()).isEqualTo("quotes"));
            // long keys hint 8 bytes; an interface without key methods falls back to the LMDB limit.
            assertThat(bindings.stream().map(GeneratedKvStoreBinding::keyLengthHint))
                    .containsExactly(8, 8, 511);
            assertThat(bindings.stream().map(b -> b.storeType().getSimpleName()))
                    .containsExactly("QuoteReader", "QuoteWriter", "QuoteWriterOps");
            assertThat(bindings.stream().map(GeneratedKvStoreBinding::reads)).containsExactly(true, false, false);
            assertThat(bindings.stream().map(GeneratedKvStoreBinding::writes)).containsExactly(false, true, true);
            // Reader-only bindings carry no writer config; the runtime declares one reader and one
            // (idempotent) writer per store name.
            assertThat(bindings.getFirst().storeConfig()).isNull();
            assertThat(bindings.stream()
                            .filter(GeneratedKvStoreBinding::reads)
                            .map(GeneratedKvStoreBinding::store)
                            .distinct())
                    .containsExactly("quotes");
            assertThat(bindings.stream()
                            .filter(GeneratedKvStoreBinding::writes)
                            .map(GeneratedKvStoreBinding::store)
                            .distinct())
                    .containsExactly("quotes");
        }
    }

    @Test
    void dispatchesWriterStateToHandlersOfTheEventStore() throws Exception {
        String recorder = """
                package test;
                public final class Recorder {
                  public static final java.util.List<String> EVENTS = new java.util.concurrent.CopyOnWriteArrayList<>();
                  private Recorder() {}
                }
                """;
        Compiled compiled = compile(
                "dispatch",
                source("test.KvApp", APP_SRC),
                source("test.Quote", QUOTE_SRC),
                source("test.Recorder", recorder),
                source("test.PriceWriter", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvWriterOperations;
                        @RingloomKvStore(name = "prices")
                        public interface PriceWriter extends KvWriterOperations {}
                        """),
                source("test.AuditWriter", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "audit", serializer = "fory")
                        public interface AuditWriter {
                          @RingloomKvPut int put(String id, Quote quote);
                        }
                        """),
                source("test.PriceLifecycle", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvWriterStateEvent;
                        @RingloomServiceComponent
                        public final class PriceLifecycle {
                          @RingloomKvWriterStateHandler(store = "prices")
                          public void onPrices(KvWriterStateEvent event) {
                            Recorder.EVENTS.add("prices:" + event.state());
                            throw new IllegalStateException("boom");
                          }
                          @RingloomKvWriterStateHandler(store = "audit")
                          public void onAudit(KvWriterStateEvent event) { Recorder.EVENTS.add("audit:" + event.state()); }
                        }
                        """),
                source("test.SecondLifecycle", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvWriterStateEvent;
                        public final class SecondLifecycle {
                          @RingloomKvWriterStateHandler(store = "prices")
                          public void onPrices(KvWriterStateEvent event) { Recorder.EVENTS.add("second:" + event.writerEpoch()); }
                        }
                        """));

        assertThat(compiled.result.success()).as("compilation should succeed").isTrue();
        assertThat(compiled.generatedSource("test/KvApp_RingloomApplication.java"))
                .contains("switch (event.store())")
                .contains("case \"prices\" -> {")
                .contains("component(test.PriceLifecycle.class).onPrices(event);")
                .contains("component(test.SecondLifecycle.class).onPrices(event);")
                .contains("case \"audit\" -> {");
        try (URLClassLoader loader = compiled.loader()) {
            GeneratedRingloomApplication app = compiled.application(loader);
            @SuppressWarnings("unchecked")
            List<String> events = (List<String>)
                    loader.loadClass("test.Recorder").getField("EVENTS").get(null);

            Throwable failure = null;
            try {
                app.dispatchKvWriterState(
                        new KvWriterStateEvent("prices", KvWriterState.GRANTED, KvWriterState.QUEUED, 7));
            } catch (IllegalStateException ex) {
                failure = ex;
            }
            // A throwing handler does not stop the other handlers of the store; the first failure is rethrown.
            assertThat(failure).hasMessage("boom");
            assertThat(events).containsExactly("prices:GRANTED", "second:7");

            events.clear();
            app.dispatchKvWriterState(new KvWriterStateEvent("audit", KvWriterState.REVOKED, KvWriterState.GRANTED, 3));
            app.dispatchKvWriterState(new KvWriterStateEvent("other", KvWriterState.GRANTED, KvWriterState.QUEUED, 1));
            assertThat(events).containsExactly("audit:REVOKED");
        }
    }

    @Test
    void warnsWhenNoSerializerIsDeclaredForAValue() throws Exception {
        Compiled compiled = compile(
                "warn", source("test.KvApp", APP_SRC), source("test.Quote", QUOTE_SRC), source("test.QuoteStore", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "quotes")
                        public interface QuoteStore {
                          @RingloomKvGet Quote get(long id);
                        }
                        """));

        assertThat(compiled.result.success()).isTrue();
        assertThat(compiled.result.warnings())
                .anyMatch(w -> w.contains("the runtime default serializer is used for test.Quote"));
    }

    static Stream<Arguments> invalidStores() {
        return Stream.of(
                // Rule 1: non-interface; method without (or with two) KV annotations.
                Arguments.of("non-interface", """
                        @RingloomKvStore(name = "s")
                        public abstract class S {}
                        """, "@RingloomKvStore may only annotate interfaces"),
                Arguments.of("unannotated-method", """
                        @RingloomKvStore(name = "s", serializer = "fory")
                        public interface S { Quote get(long id); }
                        """, "must carry exactly one KV annotation"),
                Arguments.of("two-annotations", """
                        @RingloomKvStore(name = "s", serializer = "fory")
                        public interface S { @RingloomKvGet @RingloomKvContains boolean get(long id); }
                        """, "must carry exactly one KV annotation"),
                // Rule 2: store name.
                Arguments.of("bad-name", """
                        @RingloomKvStore(name = "bad name!")
                        public interface S { @RingloomKvContains boolean contains(long id); }
                        """, "is invalid: expected 1..64 bytes"),
                Arguments.of("long-name", """
                        @RingloomKvStore(name = "%s")
                        public interface S { @RingloomKvContains boolean contains(long id); }
                        """.formatted("x".repeat(65)), "is invalid: expected 1..64 bytes"),
                // Rule 3: signatures, ack parameters, TTL parameters.
                Arguments.of("bad-get", """
                        @RingloomKvStore(name = "s", serializer = "fory")
                        public interface S { @RingloomKvGet int get(long id, String other); }
                        """, "unsupported @RingloomKvGet signature"),
                Arguments.of("bad-contains", """
                        @RingloomKvStore(name = "s")
                        public interface S { @RingloomKvContains int contains(long id); }
                        """, "unsupported @RingloomKvContains signature"),
                Arguments.of(
                        "put-missing-ack",
                        """
                        @RingloomKvStore(name = "s", serializer = "fory")
                        public interface S {
                          @RingloomKvPut(ackMode = io.ringloom.service.TopicAckMode.REPLICATE_ONCE)
                          int put(long id, Quote quote);
                        }
                        """,
                        "with REPLICATE_ONCE must declare (AckCallback callback, Object context)"),
                Arguments.of("delete-ack-fire-and-forget", """
                        @RingloomKvStore(name = "s")
                        public interface S {
                          @RingloomKvDelete
                          int delete(long id, io.ringloom.framework.topic.ack.AckCallback cb, Object ctx);
                        }
                        """, "ackMode is FIRE_AND_FORGET"),
                Arguments.of("two-ttl", """
                        @RingloomKvStore(name = "s", serializer = "fory")
                        public interface S {
                          @RingloomKvPut int put(long id, Quote quote, @RingloomKvTtl long a, @RingloomKvTtl long b);
                        }
                        """, "more than one @RingloomKvTtl parameter"),
                // Rule 4: non-built-in key without keySerializer.
                Arguments.of("key-serializer", """
                        @RingloomKvStore(name = "s")
                        public interface S { @RingloomKvContains boolean contains(Quote key); }
                        """, "has no built-in encoding"),
                // Rule 8: key length limits.
                Arguments.of("max-key-too-large", """
                        @RingloomKvStore(name = "s", maxKeyLength = 600)
                        public interface S { @RingloomKvContains boolean contains(String id); }
                        """, "maxKeyLength must be between 1 and 511"),
                Arguments.of("max-key-long", """
                        @RingloomKvStore(name = "s", maxKeyLength = 4)
                        public interface S { @RingloomKvContains boolean contains(long id); }
                        """, "maxKeyLength must be at least 8 for long keys"),
                Arguments.of("max-key-int", """
                        @RingloomKvStore(name = "s", maxKeyLength = 2)
                        public interface S { @RingloomKvContains boolean contains(int id); }
                        """, "maxKeyLength must be at least 4 for int keys"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStores")
    void rejectsInvalidStore(String name, String body, String expectedError) throws Exception {
        Compiled compiled = compile(
                "invalid-" + name,
                source("test.Quote", QUOTE_SRC),
                source("test.S", "package test;\nimport io.ringloom.framework.annotation.*;\n" + body));

        assertThat(compiled.result.success()).isFalse();
        assertThat(compiled.result.errors()).anyMatch(error -> error.contains(expectedError));
    }

    @Test
    void rejectsSameStoreWithDifferentWriterConfig() throws Exception {
        assertRejected(
                "merged-config",
                "is declared with a different writer config",
                source("test.A", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "s", maxValueLength = 10)
                        public interface A { @RingloomKvDelete int delete(long id); }
                        """),
                source("test.B", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "s", maxValueLength = 20)
                        public interface B { @RingloomKvDelete int delete(long id); }
                        """));
    }

    @Test
    void rejectsSameStoreWithDifferentKeyTypes() throws Exception {
        assertRejected(
                "merged-keys", "different key type or keySerializer", source("test.A", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "s")
                        public interface A { @RingloomKvContains boolean contains(long id); }
                        """), source("test.B", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "s")
                        public interface B { @RingloomKvContains boolean contains(String id); }
                        """));
    }

    @Test
    void rejectsWriterStateHandlerForStoreWithoutWriter() throws Exception {
        assertRejected(
                "handler-no-writer",
                "store 'prices' is not written by any @RingloomKvStore interface",
                source("test.KvApp", APP_SRC),
                source("test.PriceReader", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomKvStore(name = "prices")
                        public interface PriceReader { @RingloomKvContains boolean contains(long id); }
                        """),
                source("test.Lifecycle", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvWriterStateEvent;
                        @RingloomServiceComponent
                        public final class Lifecycle {
                          @RingloomKvWriterStateHandler(store = "prices")
                          public void on(KvWriterStateEvent event) {}
                        }
                        """));
    }

    @Test
    void rejectsWriterStateHandlerWithWrongSignatureOrVisibility() throws Exception {
        assertRejected(
                "handler-signature",
                "signature must be: public void m(KvWriterStateEvent event)",
                writerSource(),
                source("test.Lifecycle", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        @RingloomServiceComponent
                        public final class Lifecycle {
                          @RingloomKvWriterStateHandler(store = "prices")
                          public int on(String event) { return 0; }
                        }
                        """));
        assertRejected(
                "handler-visibility", "must be public instance methods", writerSource(), source("test.Lifecycle", """
                        package test;
                        import io.ringloom.framework.annotation.*;
                        import io.ringloom.framework.kv.KvWriterStateEvent;
                        @RingloomServiceComponent
                        public final class Lifecycle {
                          @RingloomKvWriterStateHandler(store = "prices")
                          void on(KvWriterStateEvent event) {}
                        }
                        """));
    }

    private static SimpleJavaFileObject writerSource() {
        return source("test.PriceWriter", """
                package test;
                import io.ringloom.framework.annotation.*;
                @RingloomKvStore(name = "prices")
                public interface PriceWriter { @RingloomKvClear int clear(); }
                """);
    }

    private void assertRejected(String name, String expectedError, SimpleJavaFileObject... sources) throws Exception {
        List<SimpleJavaFileObject> all = new ArrayList<>(List.of(sources));
        Compiled compiled = compile(name, all.toArray(SimpleJavaFileObject[]::new));
        assertThat(compiled.result.success()).isFalse();
        assertThat(compiled.result.errors()).anyMatch(error -> error.contains(expectedError));
    }

    private static SimpleJavaFileObject source(String className, String content) {
        return ProcessorTestSupport.source(className, content);
    }

    private Compiled compile(String name, SimpleJavaFileObject... sources) throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve(name + "-classes"));
        Path generated = Files.createDirectories(tempDir.resolve(name + "-generated"));
        return new Compiled(
                classes, generated, ProcessorTestSupport.compileWithDiagnostics(classes, generated, List.of(sources)));
    }

    private static String golden() throws Exception {
        try (InputStream input = KvCodegenTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(input).as("golden resource " + GOLDEN).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Compiled(Path classes, Path generated, ProcessorTestSupport.Result result) {
        String generatedSource(String path) throws Exception {
            return Files.readString(generated.resolve(path));
        }

        URLClassLoader loader() throws Exception {
            return new URLClassLoader(new URL[] {classes.toUri().toURL()}, KvCodegenTest.class.getClassLoader());
        }

        GeneratedRingloomApplication application(ClassLoader loader) throws Exception {
            return (GeneratedRingloomApplication) loader.loadClass("test.KvApp_RingloomApplication")
                    .getDeclaredConstructor()
                    .newInstance();
        }
    }
}
