// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

/**
 * A writer lease state transition, dispatched on the control thread after native control polling.
 *
 * @param store       the store name
 * @param state       the new state
 * @param previous    the previous state, or {@code null} for the initial state reported after open
 * @param writerEpoch the lease epoch ({@code 0} when not granted; kept while reasserting)
 */
public record KvWriterStateEvent(String store, KvWriterState state, KvWriterState previous, long writerEpoch) {}
