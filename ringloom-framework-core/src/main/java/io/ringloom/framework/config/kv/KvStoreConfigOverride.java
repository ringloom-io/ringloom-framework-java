// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.kv;

import io.ringloom.service.KvStoreConfig;
import java.util.Objects;

/**
 * Optional overrides applied over a writer's {@link KvStoreConfig} (native defaults, then annotation
 * values, then these overrides). Negative numbers and an empty roll scheme mean "not overridden".
 *
 * @param maxKeyLength     maximum key length in bytes (1..511), or {@code -1}
 * @param maxValueLength   maximum value length in bytes, or {@code -1}
 * @param defaultTtlMillis default entry TTL ({@code 0} = never), or {@code -1}
 * @param mapSizeHint      LMDB map size hint in bytes, or {@code -1}
 * @param rollScheme       backing topic roll scheme, or {@code ""}
 */
public record KvStoreConfigOverride(
        int maxKeyLength, long maxValueLength, long defaultTtlMillis, long mapSizeHint, String rollScheme) {

    private static final KvStoreConfigOverride NONE = new KvStoreConfigOverride(-1, -1, -1, -1, "");

    public KvStoreConfigOverride {
        rollScheme = rollScheme == null ? "" : rollScheme;
        if (maxKeyLength != -1 && (maxKeyLength < 1 || maxKeyLength > 511)) {
            throw new IllegalArgumentException("kv config maxKeyLength must be in 1..511");
        }
        if (maxValueLength < -1 || maxValueLength > 0xffffffffL) {
            throw new IllegalArgumentException("kv config maxValueLength must be >= 0");
        }
        if (defaultTtlMillis < -1) {
            throw new IllegalArgumentException("kv config defaultTtlMillis must be >= 0");
        }
        if (mapSizeHint < -1) {
            throw new IllegalArgumentException("kv config mapSizeHint must be >= 0");
        }
    }

    /**
     * Returns the override that changes nothing.
     *
     * @return the empty override
     */
    public static KvStoreConfigOverride none() {
        return NONE;
    }

    /**
     * Returns whether this override changes nothing.
     *
     * @return {@code true} when no field is overridden
     */
    public boolean isEmpty() {
        return maxKeyLength == -1
                && maxValueLength == -1
                && defaultTtlMillis == -1
                && mapSizeHint == -1
                && rollScheme.isEmpty();
    }

    /**
     * Applies the overridden fields over a base config.
     *
     * @param base the base config (native defaults with annotation values applied)
     * @return the effective config
     */
    public KvStoreConfig applyTo(KvStoreConfig base) {
        Objects.requireNonNull(base, "base");
        if (isEmpty()) {
            return base;
        }
        return new KvStoreConfig(
                base.formatVersion(),
                maxKeyLength == -1 ? base.maxKeyLength() : maxKeyLength,
                maxValueLength == -1 ? base.maxValueLength() : maxValueLength,
                defaultTtlMillis == -1 ? base.defaultTtlMillis() : defaultTtlMillis,
                mapSizeHint == -1 ? base.mapSizeHint() : mapSizeHint,
                rollScheme.isEmpty() ? base.topicRollScheme() : rollScheme,
                base.flags());
    }
}
