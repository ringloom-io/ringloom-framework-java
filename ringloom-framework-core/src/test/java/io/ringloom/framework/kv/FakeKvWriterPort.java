// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.service.KvStoreConfig;
import io.ringloom.service.RingloomStatus;
import io.ringloom.service.TopicAckMode;
import java.lang.foreign.MemorySegment;

/** In-memory {@link KvWriterPort} for unit tests: assigns sequences and exposes ack/epoch knobs. */
final class FakeKvWriterPort implements KvWriterPort {
    static final KvStoreConfig CONFIG = new KvStoreConfig(1, 64, 1024, 0, 1 << 20, "hourly", 0);

    volatile int nextStatus = RingloomStatus.OK;
    volatile long ackedSequence;
    volatile long leaderEpoch = 1;
    volatile long writerEpoch = 1;
    volatile int state = 0;
    long lastSequence;
    int puts;
    boolean inBatch;
    boolean closed;
    int batchedRecords;

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
        return write(ack, outSequence);
    }

    @Override
    public int delete(MemorySegment key, long keyOffset, long keyLength, TopicAckMode ack, long[] outSequence) {
        return write(ack, outSequence);
    }

    @Override
    public int clear(TopicAckMode ack, long[] outSequence) {
        return write(ack, outSequence);
    }

    private int write(TopicAckMode ack, long[] outSequence) {
        check();
        int status = nextStatus;
        outSequence[0] = 0;
        if (status != RingloomStatus.OK) {
            return status;
        }
        puts++;
        if (inBatch) {
            batchedRecords++;
            return status;
        }
        outSequence[0] = ++lastSequence;
        return status;
    }

    @Override
    public int beginBatch() {
        check();
        inBatch = true;
        return RingloomStatus.OK;
    }

    @Override
    public int commitBatch(TopicAckMode ack, long[] outSequence) {
        check();
        int status = nextStatus;
        if (status != RingloomStatus.OK) {
            outSequence[0] = 0;
            return status;
        }
        inBatch = false;
        batchedRecords = 0;
        outSequence[0] = ++lastSequence;
        return status;
    }

    @Override
    public int abandonBatch() {
        check();
        inBatch = false;
        batchedRecords = 0;
        return RingloomStatus.OK;
    }

    @Override
    public long ackedSequence() {
        return ackedSequence;
    }

    @Override
    public long leaderEpoch() {
        return leaderEpoch;
    }

    @Override
    public long writerEpoch() {
        return writerEpoch;
    }

    @Override
    public int state() {
        return state;
    }

    @Override
    public KvStoreConfig config() {
        return CONFIG;
    }

    @Override
    public void close() {
        closed = true;
    }

    private void check() {
        if (closed) {
            throw new IllegalStateException("KvWriter closed");
        }
    }
}
