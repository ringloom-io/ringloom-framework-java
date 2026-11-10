// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.kv.codec.KvKeyCodecs;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class KvValueTest {
    @Test
    void tracksLastResult() {
        KvValue<String> holder = new KvValue<>();
        assertThat(holder.value()).isNull();
        assertThat(holder.isOk()).isFalse();

        holder.ok("v", 3);
        assertThat(holder.value()).isEqualTo("v");
        assertThat(holder.length()).isEqualTo(3);
        assertThat(holder.status()).isZero();
        assertThat(holder.isOk()).isTrue();

        holder.fail(42);
        assertThat(holder.value()).isNull();
        assertThat(holder.length()).isZero();
        assertThat(holder.status()).isEqualTo(42);
        assertThat(holder.isOk()).isFalse();
    }

    @Test
    void visitorDecodesRawKeys() {
        List<Long> keys = new ArrayList<>();
        KvEntryVisitor<String> visitor = (keyBytes, value) -> {
            keys.add(KvKeyCodecs.decodeLong(keyBytes));
            return keys.size() < 2;
        };
        MemorySegment key = MemorySegment.ofArray(new byte[8]);

        KvKeyCodecs.encodeLong(-9L, key, 8);
        assertThat(visitor.visit(key, "a")).isTrue();
        assertThat(visitor.visit(key, "b")).isFalse();
        assertThat(keys).containsExactly(-9L, -9L);
    }
}
