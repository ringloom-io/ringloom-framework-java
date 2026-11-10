// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RingloomKvClear;
import io.ringloom.framework.annotation.RingloomKvContains;
import io.ringloom.framework.annotation.RingloomKvDelete;
import io.ringloom.framework.annotation.RingloomKvForEach;
import io.ringloom.framework.annotation.RingloomKvGet;
import io.ringloom.framework.annotation.RingloomKvPut;
import io.ringloom.framework.annotation.RingloomKvStore;
import io.ringloom.framework.annotation.RingloomKvTtl;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.KvMethod;
import io.ringloom.framework.processor.model.KvMethod.Shape;
import io.ringloom.framework.processor.model.KvStore;
import io.ringloom.framework.processor.model.KvStore.KeyKind;
import io.ringloom.framework.processor.model.SbePayloadModel;
import io.ringloom.framework.processor.model.Symbols;
import io.ringloom.service.TopicAckMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Validates {@link RingloomKvStore} interfaces (§9.3 rules 1–6 and 8) and builds the {@link KvStore}
 * model. Errors are reported on the processor context; a model is always returned.
 */
public final class KvStoreValidator {
    /** LMDB key limit (native §4.2). */
    public static final int LMDB_MAX_KEY_LENGTH = 511;

    private static final Pattern STORE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MAX_STORE_NAME_BYTES = 64;

    private final ProcessorContext ctx;

    public KvStoreValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns whether a store name is valid: 1..64 bytes of {@code [A-Za-z0-9._-]}.
     *
     * @param name the store name
     * @return {@code true} when valid
     */
    public static boolean isValidStoreName(String name) {
        return name != null
                && !name.isEmpty()
                && name.getBytes(StandardCharsets.UTF_8).length <= MAX_STORE_NAME_BYTES
                && STORE_NAME.matcher(name).matches();
    }

    /**
     * Validates one store interface.
     *
     * @param store the annotated element
     * @return the model
     */
    public KvStore validate(TypeElement store) {
        RingloomKvStore annotation = store.getAnnotation(RingloomKvStore.class);
        if (store.getKind() != ElementKind.INTERFACE) {
            ctx.error(store, "@RingloomKvStore may only annotate interfaces");
        }
        if (!isValidStoreName(annotation.name())) {
            ctx.error(
                    store,
                    "@RingloomKvStore name '" + annotation.name()
                            + "' is invalid: expected 1..64 bytes of [A-Za-z0-9._-]");
        }
        if (annotation.maxKeyLength() != -1
                && (annotation.maxKeyLength() < 1 || annotation.maxKeyLength() > LMDB_MAX_KEY_LENGTH)) {
            ctx.error(store, "@RingloomKvStore maxKeyLength must be between 1 and " + LMDB_MAX_KEY_LENGTH);
        }
        Types types = ctx.env().getTypeUtils();
        boolean readerOps = isSubtypeOf(store, Symbols.KV_READER_OPERATIONS);
        boolean writerOps = isSubtypeOf(store, Symbols.KV_WRITER_OPERATIONS);
        DeclaredType storeType = (DeclaredType) store.asType();

        List<KvMethod> methods = new ArrayList<>();
        TypeMirror keyType = null;
        Element keyOrigin = null;
        for (ExecutableElement method :
                ElementFilter.methodsIn(ctx.elementUtils().getAllMembers(store))) {
            if (!method.getModifiers().contains(Modifier.ABSTRACT) || isFrameworkMethod(method)) {
                continue;
            }
            int annotations = countKvAnnotations(method);
            if (annotations != 1) {
                ctx.error(
                        errorTarget(store, method),
                        "@RingloomKvStore method " + method.getSimpleName()
                                + " must carry exactly one KV annotation (@RingloomKvGet, @RingloomKvContains, "
                                + "@RingloomKvForEach, @RingloomKvPut, @RingloomKvDelete or @RingloomKvClear)");
                continue;
            }
            ExecutableType resolved = (ExecutableType) types.asMemberOf(storeType, method);
            KvMethod kvMethod = validateMethod(store, annotation, method, resolved);
            if (kvMethod == null) {
                continue;
            }
            methods.add(kvMethod);
            if (kvMethod.keyName() != null) {
                TypeMirror methodKey = resolved.getParameterTypes().getFirst();
                if (keyType == null) {
                    keyType = methodKey;
                    keyOrigin = method;
                } else if (!types.isSameType(keyType, methodKey)) {
                    ctx.error(
                            errorTarget(store, method),
                            "@RingloomKvStore methods must all use the same key type: " + keyType + " (declared by "
                                    + keyOrigin.getSimpleName() + ") vs " + methodKey);
                }
            }
        }

        KeyKind keyKind = keyType == null ? null : keyKind(keyType);
        if (keyKind == KeyKind.SERIALIZER) {
            if (annotation.keySerializer().isBlank()) {
                ctx.error(
                        store,
                        "@RingloomKvStore key type " + keyType
                                + " has no built-in encoding (long, int, String, MemorySegment, byte[]); declare"
                                + " keySerializer");
            }
            if (!isPlainClass(keyType)) {
                ctx.error(store, "@RingloomKvStore key type " + keyType + " must be a non-generic class");
            }
        }
        if (keyKind == KeyKind.LONG && annotation.maxKeyLength() > 0 && annotation.maxKeyLength() < Long.BYTES) {
            ctx.error(store, "@RingloomKvStore maxKeyLength must be at least 8 for long keys");
        }
        if (keyKind == KeyKind.INT && annotation.maxKeyLength() > 0 && annotation.maxKeyLength() < Integer.BYTES) {
            ctx.error(store, "@RingloomKvStore maxKeyLength must be at least 4 for int keys");
        }
        return new KvStore(
                store,
                annotation.name(),
                keyKind,
                keyType == null ? null : keyType.toString(),
                keyKind == KeyKind.SERIALIZER ? annotation.keySerializer() : "",
                readerOps,
                writerOps,
                annotation.maxKeyLength(),
                annotation.maxValueLength(),
                annotation.defaultTtlMillis(),
                annotation.mapSizeHint(),
                annotation.rollScheme(),
                List.copyOf(methods));
    }

    /**
     * Checks §9.3 rule 6 across all store interfaces of the compilation: interfaces naming the same
     * store must agree on key type and key serializer, and writing interfaces on the writer config.
     *
     * @param stores the validated stores
     */
    public void validateMerged(List<KvStore> stores) {
        Map<String, KvStore> firstWithKey = new LinkedHashMap<>();
        Map<String, KvStore> firstWriter = new LinkedHashMap<>();
        for (KvStore store : stores) {
            if (store.keyType() != null) {
                KvStore previous = firstWithKey.putIfAbsent(store.name(), store);
                if (previous != null
                        && (!previous.keyType().equals(store.keyType())
                                || !previous.keySerializer().equals(store.keySerializer()))) {
                    ctx.error(
                            store.storeInterface(),
                            "kv store '" + store.name() + "' is declared with a different key type or keySerializer"
                                    + " than " + previous.storeInterface().getQualifiedName()
                                    + "; keys must encode identically");
                }
            }
            if (store.writes()) {
                KvStore previous = firstWriter.putIfAbsent(store.name(), store);
                if (previous != null && !sameWriterConfig(previous, store)) {
                    ctx.error(
                            store.storeInterface(),
                            "kv store '" + store.name() + "' is declared with a different writer config than "
                                    + previous.storeInterface().getQualifiedName());
                }
            }
        }
    }

    private static boolean sameWriterConfig(KvStore a, KvStore b) {
        return a.maxKeyLength() == b.maxKeyLength()
                && a.maxValueLength() == b.maxValueLength()
                && a.defaultTtlMillis() == b.defaultTtlMillis()
                && a.mapSizeHint() == b.mapSizeHint()
                && Objects.equals(a.rollScheme(), b.rollScheme());
    }

    private KvMethod validateMethod(
            TypeElement store, RingloomKvStore storeAnnotation, ExecutableElement method, ExecutableType resolved) {
        Element target = errorTarget(store, method);
        List<? extends VariableElement> params = method.getParameters();
        List<? extends TypeMirror> paramTypes = resolved.getParameterTypes();
        String returnType = resolved.getReturnType().toString();
        int ttlCount = 0;
        int ttlIndex = -1;
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).getAnnotation(RingloomKvTtl.class) != null) {
                ttlCount++;
                ttlIndex = i;
            }
        }
        if (ttlCount > 1) {
            ctx.error(
                    target, "KV method " + method.getSimpleName() + " declares more than one @RingloomKvTtl parameter");
            return null;
        }
        String parameters = parameterSource(params, paramTypes);

        RingloomKvGet get = method.getAnnotation(RingloomKvGet.class);
        RingloomKvContains contains = method.getAnnotation(RingloomKvContains.class);
        RingloomKvForEach forEach = method.getAnnotation(RingloomKvForEach.class);
        RingloomKvPut put = method.getAnnotation(RingloomKvPut.class);
        RingloomKvDelete delete = method.getAnnotation(RingloomKvDelete.class);
        RingloomKvClear clear = method.getAnnotation(RingloomKvClear.class);
        if (ttlCount == 1 && put == null) {
            ctx.error(target, "@RingloomKvTtl is only allowed on @RingloomKvPut methods");
            return null;
        }

        if (get != null) {
            String serializer = serializer(get.serializer(), storeAnnotation);
            if (params.size() == 1 && resolved.getReturnType().getKind() == TypeKind.DECLARED) {
                TypeMirror value = resolved.getReturnType();
                if (!validValueType(target, value, serializer, false)) {
                    return null;
                }
                return new KvMethod(
                        method,
                        Shape.GET_VALUE,
                        parameters,
                        name(params, 0),
                        raw(value),
                        null,
                        null,
                        serializer,
                        false,
                        null,
                        null,
                        null,
                        -1);
            }
            if (params.size() == 2 && returnType.equals("int") && isType(paramTypes.get(1), Symbols.KV_VALUE)) {
                TypeMirror value = typeArgument(paramTypes.get(1));
                if (value == null || !validValueType(target, value, serializer, false)) {
                    if (value == null) {
                        ctx.error(target, "KvValue parameter must declare its value type");
                    }
                    return null;
                }
                return new KvMethod(
                        method,
                        Shape.GET_HOLDER,
                        parameters,
                        name(params, 0),
                        raw(value),
                        name(params, 1),
                        null,
                        serializer,
                        false,
                        null,
                        null,
                        null,
                        -1);
            }
            if (params.size() == 3
                    && returnType.equals("int")
                    && isType(paramTypes.get(1), Symbols.MEMORY_SEGMENT)
                    && paramTypes.get(2).toString().equals("long[]")) {
                return new KvMethod(
                        method,
                        Shape.GET_RAW,
                        parameters,
                        name(params, 0),
                        null,
                        name(params, 1),
                        name(params, 2),
                        "",
                        false,
                        null,
                        null,
                        null,
                        -1);
            }
            return unsupported(
                    target,
                    "@RingloomKvGet",
                    "V get(K key), int get(K key, KvValue<V> out), or int get(K key, MemorySegment destination,"
                            + " long[] outLength)");
        }
        if (contains != null) {
            if (params.size() == 1 && returnType.equals("boolean")) {
                return new KvMethod(
                        method,
                        Shape.CONTAINS,
                        parameters,
                        name(params, 0),
                        null,
                        null,
                        null,
                        "",
                        false,
                        null,
                        null,
                        null,
                        -1);
            }
            return unsupported(target, "@RingloomKvContains", "boolean contains(K key)");
        }
        if (forEach != null) {
            if (params.size() == 1 && returnType.equals("int")) {
                if (isType(paramTypes.get(0), Symbols.KV_RAW_VISITOR)) {
                    return new KvMethod(
                            method,
                            Shape.FOR_EACH_RAW,
                            parameters,
                            null,
                            null,
                            name(params, 0),
                            null,
                            "",
                            false,
                            null,
                            null,
                            null,
                            -1);
                }
                if (isType(paramTypes.get(0), Symbols.KV_ENTRY_VISITOR)) {
                    String serializer = serializer(forEach.serializer(), storeAnnotation);
                    TypeMirror value = typeArgument(paramTypes.get(0));
                    if (value == null) {
                        ctx.error(target, "KvEntryVisitor parameter must declare its value type");
                        return null;
                    }
                    if (!validValueType(target, value, serializer, false)) {
                        return null;
                    }
                    return new KvMethod(
                            method,
                            Shape.FOR_EACH,
                            parameters,
                            null,
                            raw(value),
                            name(params, 0),
                            null,
                            serializer,
                            false,
                            null,
                            null,
                            null,
                            -1);
                }
            }
            return unsupported(
                    target,
                    "@RingloomKvForEach",
                    "int forEach(KvEntryVisitor<V> visitor) or int forEach(KvRawVisitor visitor)");
        }

        TopicAckMode ackMode = put != null ? put.ackMode() : delete != null ? delete.ackMode() : clear.ackMode();
        String annotationName =
                put != null ? "@RingloomKvPut" : delete != null ? "@RingloomKvDelete" : "@RingloomKvClear";
        boolean ack = ackMode == TopicAckMode.REPLICATE_ONCE;
        if (!returnType.equals("int")) {
            ctx.error(target, annotationName + " methods must return int");
            return null;
        }
        int ackParams = countAckParams(paramTypes);
        if (ack && ackParams == 0) {
            ctx.error(
                    target,
                    annotationName + " with REPLICATE_ONCE must declare (AckCallback callback, Object context)"
                            + " as the last two parameters");
            return null;
        }
        if (!ack && ackParams > 0) {
            ctx.error(
                    target,
                    annotationName + " declares ack parameters but its ackMode is FIRE_AND_FORGET;"
                            + " set ackMode = REPLICATE_ONCE");
            return null;
        }
        int size = params.size();
        if (ack
                && (ackParams != 1
                        || size < 2
                        || !isType(paramTypes.get(size - 2), Symbols.ACK_CALLBACK)
                        || !paramTypes.get(size - 1).toString().equals("java.lang.Object"))) {
            ctx.error(
                    target,
                    annotationName + " ack parameters must be the last two: (AckCallback callback, Object context)");
            return null;
        }
        String callbackName = ack ? name(params, size - 2) : null;
        String contextName = ack ? name(params, size - 1) : null;
        int core = ack ? size - 2 : size;

        if (put != null) {
            String serializer = serializer(put.serializer(), storeAnnotation);
            String ttlName = null;
            if (ttlIndex >= 0) {
                if (ttlIndex != 2 || paramTypes.get(ttlIndex).getKind() != TypeKind.LONG) {
                    ctx.error(target, "@RingloomKvTtl must be a long parameter directly after the value");
                    return null;
                }
                ttlName = name(params, ttlIndex);
            }
            int expected = ttlIndex >= 0 ? 3 : 2;
            if (core != expected) {
                return unsupported(
                        target,
                        annotationName,
                        "int put(K key, V value [, @RingloomKvTtl long ttlMillis] [, AckCallback callback, Object"
                                + " context])");
            }
            TypeMirror value = paramTypes.get(1);
            if (isType(value, Symbols.MEMORY_SEGMENT)) {
                return new KvMethod(
                        method,
                        Shape.PUT_SEGMENT,
                        parameters,
                        name(params, 0),
                        null,
                        name(params, 1),
                        null,
                        "",
                        ack,
                        callbackName,
                        contextName,
                        ttlName,
                        put.ttlMillis());
            }
            if (!validValueType(target, value, serializer, true)) {
                return null;
            }
            return new KvMethod(
                    method,
                    Shape.PUT,
                    parameters,
                    name(params, 0),
                    raw(value),
                    name(params, 1),
                    null,
                    serializer,
                    ack,
                    callbackName,
                    contextName,
                    ttlName,
                    put.ttlMillis());
        }
        if (delete != null) {
            if (core != 1) {
                return unsupported(
                        target, annotationName, "int delete(K key [, AckCallback callback, Object context])");
            }
            return new KvMethod(
                    method,
                    Shape.DELETE,
                    parameters,
                    name(params, 0),
                    null,
                    null,
                    null,
                    "",
                    ack,
                    callbackName,
                    contextName,
                    null,
                    -1);
        }
        if (core != 0) {
            return unsupported(target, annotationName, "int clear([AckCallback callback, Object context])");
        }
        return new KvMethod(
                method, Shape.CLEAR, parameters, null, null, null, null, "", ack, callbackName, contextName, null, -1);
    }

    private KvMethod unsupported(Element target, String annotation, String accepted) {
        ctx.error(target, "unsupported " + annotation + " signature; expected " + accepted);
        return null;
    }

    private boolean validValueType(Element target, TypeMirror value, String serializer, boolean encode) {
        if (!isPlainClass(value)) {
            ctx.error(target, "KV value type " + value + " must be a non-generic class");
            return false;
        }
        String valueType = value.toString();
        if (valueType.equals(Symbols.MEMORY_SEGMENT)) {
            ctx.error(target, "MemorySegment values are only supported by put and the raw get shape");
            return false;
        }
        if (SbePayloadModel.usesSbe(serializer, valueType)) {
            if (encode) {
                SbeValidator.requireSbeDtoPayload(valueType, target, ctx.elementUtils(), ctx);
            } else {
                SbeValidator.requireSbePayload(valueType, target, ctx.elementUtils(), ctx);
            }
        } else if (serializer.isBlank()) {
            ctx.messager()
                    .printMessage(
                            Diagnostic.Kind.WARNING,
                            "KV method has no serializer on the method or store; the runtime default serializer is"
                                    + " used for " + valueType,
                            target);
        }
        return true;
    }

    private static String serializer(String methodSerializer, RingloomKvStore store) {
        return methodSerializer.isBlank() ? store.serializer() : methodSerializer;
    }

    private static int countAckParams(List<? extends TypeMirror> paramTypes) {
        int count = 0;
        for (TypeMirror type : paramTypes) {
            if (type.toString().equals(Symbols.ACK_CALLBACK)) {
                count++;
            }
        }
        return count;
    }

    private static int countKvAnnotations(ExecutableElement method) {
        int count = 0;
        count += method.getAnnotation(RingloomKvGet.class) != null ? 1 : 0;
        count += method.getAnnotation(RingloomKvContains.class) != null ? 1 : 0;
        count += method.getAnnotation(RingloomKvForEach.class) != null ? 1 : 0;
        count += method.getAnnotation(RingloomKvPut.class) != null ? 1 : 0;
        count += method.getAnnotation(RingloomKvDelete.class) != null ? 1 : 0;
        count += method.getAnnotation(RingloomKvClear.class) != null ? 1 : 0;
        return count;
    }

    private static boolean isFrameworkMethod(ExecutableElement method) {
        Element owner = method.getEnclosingElement();
        if (!(owner instanceof TypeElement type)) {
            return false;
        }
        String name = type.getQualifiedName().toString();
        return name.equals("java.lang.Object")
                || name.equals(Symbols.KV_READER_OPERATIONS)
                || name.equals(Symbols.KV_WRITER_OPERATIONS);
    }

    private boolean isSubtypeOf(TypeElement type, String superName) {
        TypeElement superType = ctx.elementUtils().getTypeElement(superName);
        return superType != null && ctx.env().getTypeUtils().isAssignable(type.asType(), superType.asType());
    }

    private static Element errorTarget(TypeElement store, ExecutableElement method) {
        return method.getEnclosingElement().equals(store) ? method : store;
    }

    private static KeyKind keyKind(TypeMirror type) {
        if (type.getKind() == TypeKind.LONG) {
            return KeyKind.LONG;
        }
        if (type.getKind() == TypeKind.INT) {
            return KeyKind.INT;
        }
        if (type instanceof ArrayType array && array.getComponentType().getKind() == TypeKind.BYTE) {
            return KeyKind.BYTES;
        }
        String name = type.toString();
        if (name.equals("java.lang.String")) {
            return KeyKind.STRING;
        }
        if (name.equals(Symbols.MEMORY_SEGMENT)) {
            return KeyKind.SEGMENT;
        }
        return KeyKind.SERIALIZER;
    }

    private static boolean isPlainClass(TypeMirror type) {
        return type instanceof DeclaredType declared
                && declared.getTypeArguments().isEmpty();
    }

    private static boolean isType(TypeMirror type, String qualifiedName) {
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        return ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(qualifiedName);
    }

    private static TypeMirror typeArgument(TypeMirror type) {
        if (type instanceof DeclaredType declared && declared.getTypeArguments().size() == 1) {
            TypeMirror argument = declared.getTypeArguments().getFirst();
            return argument.getKind() == TypeKind.DECLARED ? argument : null;
        }
        return null;
    }

    private static String raw(TypeMirror type) {
        return ((TypeElement) ((DeclaredType) type).asElement())
                .getQualifiedName()
                .toString();
    }

    private static String name(List<? extends VariableElement> params, int index) {
        return params.get(index).getSimpleName().toString();
    }

    private static String parameterSource(List<? extends VariableElement> params, List<? extends TypeMirror> types) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(types.get(i)).append(' ').append(params.get(i).getSimpleName());
        }
        return out.toString();
    }
}
