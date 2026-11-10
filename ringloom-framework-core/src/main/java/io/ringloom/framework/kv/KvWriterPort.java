// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.service.KvStoreConfig;
import io.ringloom.service.KvWriter;
import io.ringloom.service.TopicAckMode;
import java.lang.foreign.MemorySegment;

/** Package-private facade over the binding {@link KvWriter} so lease and ack logic can be unit tested. */
interface KvWriterPort {
    int put(
            MemorySegment key,
            long keyOffset,
            long keyLength,
            MemorySegment value,
            long valueOffset,
            long valueLength,
            long ttlMillis,
            TopicAckMode ack,
            long[] outSequence);

    int delete(MemorySegment key, long keyOffset, long keyLength, TopicAckMode ack, long[] outSequence);

    int clear(TopicAckMode ack, long[] outSequence);

    int beginBatch();

    int commitBatch(TopicAckMode ack, long[] outSequence);

    int abandonBatch();

    long ackedSequence();

    long leaderEpoch();

    long writerEpoch();

    int state();

    KvStoreConfig config();

    void close();

    static KvWriterPort of(KvWriter writer) {
        return new Binding(writer);
    }

    /** Delegates to the binding writer. */
    final class Binding implements KvWriterPort {
        private final KvWriter writer;

        Binding(KvWriter writer) {
            this.writer = writer;
        }

        @Override
        public int put(
                MemorySegment key,
                long keyOffset,
                long keyLength,
                MemorySegment value,
                long valueOffset,
                long valueLength,
                long ttlMillis,
                TopicAckMode ack,
                long[] outSequence) {
            return writer.put(key, keyOffset, keyLength, value, valueOffset, valueLength, ttlMillis, ack, outSequence);
        }

        @Override
        public int delete(MemorySegment key, long keyOffset, long keyLength, TopicAckMode ack, long[] outSequence) {
            return writer.delete(key, keyOffset, keyLength, ack, outSequence);
        }

        @Override
        public int clear(TopicAckMode ack, long[] outSequence) {
            return writer.clear(ack, outSequence);
        }

        @Override
        public int beginBatch() {
            return writer.beginBatch();
        }

        @Override
        public int commitBatch(TopicAckMode ack, long[] outSequence) {
            return writer.commitBatch(ack, outSequence);
        }

        @Override
        public int abandonBatch() {
            return writer.abandonBatch();
        }

        @Override
        public long ackedSequence() {
            return writer.ackedSequence();
        }

        @Override
        public long leaderEpoch() {
            return writer.leaderEpoch();
        }

        @Override
        public long writerEpoch() {
            return writer.writerEpoch();
        }

        @Override
        public int state() {
            return writer.state();
        }

        @Override
        public KvStoreConfig config() {
            return writer.config();
        }

        @Override
        public void close() {
            writer.close();
        }
    }
}
