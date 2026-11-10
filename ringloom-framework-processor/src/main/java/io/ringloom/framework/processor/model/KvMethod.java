// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import javax.lang.model.element.ExecutableElement;

/**
 * Model for one KV-annotated method of a {@code @RingloomKvStore} interface.
 *
 * @param method        the method element
 * @param shape         the accepted method shape (§9.2)
 * @param parameters    the source parameter list ({@code "type name, ..."}) of the generated override
 * @param keyName       the key parameter name, or {@code null} for {@code clear}/{@code forEach}
 * @param valueType     the value type's qualified (raw) name, or {@code null} when the method has no
 *                      serializer-encoded value
 * @param valueName     the value / out-holder / destination / visitor parameter name, or {@code null}
 * @param outLengthName the raw-get {@code long[]} parameter name, or {@code null}
 * @param serializer    the compile-time value serializer (method, then store); {@code ""} uses the
 *                      runtime default
 * @param ack           whether the method publishes with {@code REPLICATE_ONCE}
 * @param callbackName  the ack callback parameter name, or {@code null}
 * @param contextName   the ack context parameter name, or {@code null}
 * @param ttlName       the {@code @RingloomKvTtl} parameter name, or {@code null}
 * @param ttlMillis     the annotation TTL used when there is no TTL parameter
 */
public record KvMethod(
        ExecutableElement method,
        Shape shape,
        String parameters,
        String keyName,
        String valueType,
        String valueName,
        String outLengthName,
        String serializer,
        boolean ack,
        String callbackName,
        String contextName,
        String ttlName,
        long ttlMillis) {

    /** Accepted method shapes. */
    public enum Shape {
        GET_VALUE(false),
        GET_HOLDER(false),
        GET_RAW(false),
        CONTAINS(false),
        FOR_EACH(false),
        FOR_EACH_RAW(false),
        PUT(true),
        PUT_SEGMENT(true),
        DELETE(true),
        CLEAR(true);

        private final boolean write;

        Shape(boolean write) {
            this.write = write;
        }

        /**
         * Returns whether the shape needs the store writer.
         *
         * @return {@code true} for write shapes
         */
        public boolean write() {
            return write;
        }
    }

    /**
     * Returns the method name.
     *
     * @return the method name
     */
    public String name() {
        return method.getSimpleName().toString();
    }

    /**
     * Returns whether the method needs a value codec resolved in the proxy constructor.
     *
     * @return {@code true} when {@link #valueType()} is set
     */
    public boolean hasCodec() {
        return valueType != null;
    }
}
