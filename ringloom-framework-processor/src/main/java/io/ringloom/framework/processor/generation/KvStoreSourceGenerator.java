// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.TemplateRenderer;
import io.ringloom.framework.processor.model.KvMethod;
import io.ringloom.framework.processor.model.KvMethod.Shape;
import io.ringloom.framework.processor.model.KvStore;
import io.ringloom.framework.processor.model.KvStore.KeyKind;
import io.ringloom.framework.processor.model.SbePayloadModel;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.Symbols;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.TypeElement;

/**
 * Generates the {@code <Interface>_RingloomKvStore} proxy for a
 * {@link io.ringloom.framework.annotation.RingloomKvStore} interface. Built-in keys are encoded
 * inline in every method (no codec dispatch, no boxing); value codecs are resolved once in the proxy
 * constructor.
 */
public final class KvStoreSourceGenerator {
    private final ProcessorContext ctx;
    private final TemplateRenderer templates;

    public KvStoreSourceGenerator(ProcessorContext ctx, TemplateRenderer templates) {
        this.ctx = ctx;
        this.templates = templates;
    }

    /**
     * Returns the serializer name the generated code resolves at runtime for a value type: SBE-looking
     * types (or an explicit {@code sbe}) use {@code "sbe"}, everything else the declared name (blank
     * resolves to the runtime default).
     *
     * @param method the KV method
     * @return the serializer name
     */
    public static String effectiveSerializer(KvMethod method) {
        if (SbePayloadModel.usesSbe(method.serializer(), method.valueType())) {
            return Symbols.SBE_SERIALIZER;
        }
        return method.serializer();
    }

    /**
     * Generates the proxy.
     *
     * @param store the validated store model
     */
    public void generate(KvStore store) {
        TypeElement storeInterface = store.storeInterface();
        String pkg = SourceHelpers.packageName(ctx.elementUtils(), storeInterface);
        String generatedName = store.generatedSimpleName();
        String qualifiedName = pkg.isEmpty() ? generatedName : pkg + "." + generatedName;
        try {
            StringBuilder fields = new StringBuilder();
            StringBuilder init = new StringBuilder();
            StringBuilder methods = new StringBuilder();
            int index = 0;
            for (KvMethod method : store.methods()) {
                String codec = "c" + index++;
                if (method.hasCodec()) {
                    codecSources(method, codec, fields, init);
                }
                methods.append(methodSource(store, method, codec));
            }
            Map<String, Object> model = new HashMap<>();
            model.put("packageName", pkg);
            model.put("generatedName", generatedName);
            model.put("interfaceType", storeInterface.getQualifiedName().toString());
            model.put("storeName", SourceHelpers.escape(store.name()));
            model.put("maxKeyLength", store.effectiveMaxKeyLength());
            model.put("reads", store.reads());
            model.put("writes", store.writes());
            model.put("readerOperations", store.readerOperations());
            model.put("writerOperations", store.writerOperations());
            model.put("hasKeySerializer", store.keyKind() == KeyKind.SERIALIZER);
            model.put("keySerializer", SourceHelpers.escape(store.keySerializer()));
            model.put("keyType", store.keyType() == null ? "" : store.keyType());
            model.put("codecFields", fields.toString());
            model.put("codecInit", init.toString());
            model.put("methodSources", methods.toString());
            new SourceWriter(ctx)
                    .writeSourceFile(qualifiedName, storeInterface, templates.render("kv-store.java.mustache", model));
        } catch (IOException ex) {
            ctx.error(storeInterface, "failed to generate kv store: " + ex.getMessage());
        }
    }

    private static void codecSources(KvMethod method, String codec, StringBuilder fields, StringBuilder init) {
        String type = method.valueType();
        String serializer =
                "runtime.resolveSerializerName(\"" + SourceHelpers.escape(effectiveSerializer(method)) + "\")";
        if (method.shape() == Shape.PUT) {
            fields.append("  private final MessageEncoder<")
                    .append(type)
                    .append("> ")
                    .append(codec)
                    .append("Encoder;\n");
            init.append("    this.")
                    .append(codec)
                    .append("Encoder = KvGeneratedSupport.requireEncoder(serializers, ")
                    .append(serializer)
                    .append(", ")
                    .append(type)
                    .append(".class, STORE);\n");
            return;
        }
        fields.append("  private final FlyweightDecoder<")
                .append(type)
                .append("> ")
                .append(codec)
                .append("Flyweight;\n");
        fields.append("  private final MessageDecoder<")
                .append(type)
                .append("> ")
                .append(codec)
                .append("Decoder;\n");
        init.append("    this.")
                .append(codec)
                .append("Flyweight = KvGeneratedSupport.flyweight(serializers, ")
                .append(serializer)
                .append(", ")
                .append(type)
                .append(".class);\n");
        init.append("    this.")
                .append(codec)
                .append("Decoder = KvGeneratedSupport.requireDecoder(\n        serializers, ")
                .append(serializer)
                .append(", ")
                .append(type)
                .append(".class, this.")
                .append(codec)
                .append("Flyweight, STORE);\n");
    }

    private String methodSource(KvStore store, KvMethod method, String codec) throws IOException {
        Map<String, Object> model = new HashMap<>();
        model.put("methodName", method.name());
        model.put("parameters", method.parameters());
        model.put("valueType", method.valueType() == null ? "" : method.valueType());
        model.put("valueName", method.valueName() == null ? "" : method.valueName());
        model.put("outLengthName", method.outLengthName() == null ? "" : method.outLengthName());
        model.put("codec", codec);
        model.put("ack", method.ack());
        model.put("callbackName", method.callbackName() == null ? "" : method.callbackName());
        model.put("contextName", method.contextName() == null ? "" : method.contextName());
        model.put("segmentValue", method.shape() == Shape.PUT_SEGMENT);
        model.put("ttl", method.ttlName() != null ? method.ttlName() : method.ttlMillis() + "L");
        model.put("keySource", method.keyName() == null ? "" : keySource(store, method));
        String template =
                switch (method.shape()) {
                    case GET_VALUE -> "kv-get-value.java.mustache";
                    case GET_HOLDER -> "kv-get-holder.java.mustache";
                    case GET_RAW -> "kv-get-raw.java.mustache";
                    case CONTAINS -> "kv-contains.java.mustache";
                    case FOR_EACH -> "kv-foreach.java.mustache";
                    case FOR_EACH_RAW -> "kv-foreach-raw.java.mustache";
                    case PUT, PUT_SEGMENT -> method.ack() ? "kv-put-ack.java.mustache" : "kv-put.java.mustache";
                    case DELETE -> "kv-delete.java.mustache";
                    case CLEAR -> "kv-clear.java.mustache";
                };
        return templates.render(template, model);
    }

    /**
     * Emits the inline key encoding: declares {@code state} (when needed), {@code keySegment} and
     * {@code long keyLength}, returning early with the shape's failure value when the key does not
     * encode.
     */
    static String keySource(KvStore store, KvMethod method) {
        String key = method.keyName();
        boolean needsScratch =
                method.shape() == Shape.GET_VALUE || method.shape() == Shape.GET_HOLDER || method.shape() == Shape.PUT;
        StringBuilder out = new StringBuilder();
        if (store.keyKind() != KeyKind.SEGMENT || needsScratch) {
            out.append("    KvThreadState $state = this.kv.threadState();\n");
        }
        switch (store.keyKind()) {
            case LONG -> {
                out.append("    MemorySegment $keySegment = $state.keyScratch();\n");
                out.append("    $keySegment.set(KvGeneratedSupport.BE_LONG, 0L, ")
                        .append(key)
                        .append(" ^ Long.MIN_VALUE);\n");
                out.append("    long $keyLength = 8L;\n");
                return out.toString();
            }
            case INT -> {
                out.append("    MemorySegment $keySegment = $state.keyScratch();\n");
                out.append("    $keySegment.set(KvGeneratedSupport.BE_INT, 0L, ")
                        .append(key)
                        .append(" ^ Integer.MIN_VALUE);\n");
                out.append("    long $keyLength = 4L;\n");
                return out.toString();
            }
            case SEGMENT -> {
                out.append("    MemorySegment $keySegment = ").append(key).append(";\n");
                out.append("    long $keyLength = $keySegment.byteSize();\n");
                out.append("    if ($keyLength > MAX_KEY_LENGTH) {\n");
                out.append("      ")
                        .append(failure(method, "RingloomStatus.KV_KEY_TOO_LARGE"))
                        .append('\n');
                out.append("    }\n");
                return out.toString();
            }
            case STRING ->
                out.append("    MemorySegment $keySegment = $state.keyScratch();\n")
                        .append("    long $keyLength = KvKeyCodecs.encodeUtf8(")
                        .append(key)
                        .append(", $keySegment, MAX_KEY_LENGTH);\n");
            case BYTES ->
                out.append("    MemorySegment $keySegment = $state.keyScratch();\n")
                        .append("    long $keyLength = KvKeyCodecs.encodeBytes(")
                        .append(key)
                        .append(", $keySegment, MAX_KEY_LENGTH);\n");
            case SERIALIZER ->
                out.append("    MemorySegment $keySegment = $state.keyScratch();\n")
                        .append("    long $keyLength = KvGeneratedSupport.encodeKey(this.keyEncoder, ")
                        .append(key)
                        .append(", $state, MAX_KEY_LENGTH);\n");
        }
        out.append("    if ($keyLength < 0) {\n");
        out.append("      ")
                .append(failure(method, "KvGeneratedSupport.keyStatus($keyLength)"))
                .append('\n');
        out.append("    }\n");
        return out.toString();
    }

    private static String failure(KvMethod method, String status) {
        return switch (method.shape()) {
            case GET_VALUE -> "return null;";
            case CONTAINS -> "return false;";
            case GET_HOLDER -> "return " + method.valueName() + ".fail(" + status + ").status();";
            default -> "return " + status + ";";
        };
    }
}
