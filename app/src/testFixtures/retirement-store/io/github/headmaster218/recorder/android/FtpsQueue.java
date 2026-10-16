package io.github.headmaster218.recorder.android;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit modeled database port for compiling the actual production RetirementStore.
 * There is no SQL execution here. Production FtpsQueue database proof validation is covered
 * separately; these tests assert reconciliation calls that gate before publishing a witness.
 */
final class FtpsQueue implements AutoCloseable {
    static final int MAX_RETIRING = 8; // Matched to production FtpsQueue by the runner.

    static final class Item {
        final String source, cleanupState;
        final long retirementGeneration;
        final byte[] cleanupBinding;
        Item(String source, long generation, String cleanupState, byte[] cleanupBinding) {
            this.source = source;
            this.retirementGeneration = generation;
            this.cleanupState = cleanupState;
            this.cleanupBinding = cleanupBinding == null ? null : cleanupBinding.clone();
        }
    }

    static final class HistoryMeta {
        final String store;
        final long generation;
        final boolean ready;
        HistoryMeta(String store, long generation, boolean ready) {
            this.store = store; this.generation = generation; this.ready = ready;
        }
    }

    static final class Model {
        HistoryMeta meta;
        final List<Item> rows = new ArrayList<Item>();
        final Map<String,Long> floors = new HashMap<String,Long>();
        final List<String> events = new ArrayList<String>();
        final List<String> validatedSources = new ArrayList<String>();
        boolean boundsRejected, proofRejected, runtimeFailure, hasRanges, open;
        int readyCalls, floorCalls, closeCalls, boundsCalls;
        Model(String store, long generation, boolean ready) {
            meta = new HistoryMeta(store, generation, ready);
        }
    }

    private final Model model;
    FtpsQueue(Context context) {
        model = (Model) context.queueModel;
        if (model.runtimeFailure) throw new IllegalStateException("Synthetic database open failure");
        if (model.open) throw new IllegalStateException("Overlapping modeled database owners");
        model.open = true;
    }
    HistoryMeta historyMeta(boolean initialize) throws IOException {
        if (!initialize) throw new IOException("Unexpected read-only metadata request in reconciliation");
        model.events.add("meta");
        return model.meta;
    }
    void validateHistoryBounds() throws IOException {
        model.events.add("bounds"); model.boundsCalls++;
        if (model.boundsRejected) throw new IOException("Modeled database history gate rejected");
    }
    boolean emptyRetirement() {
        model.events.add("empty");
        return !model.hasRanges && model.floors.isEmpty() && model.rows.isEmpty();
    }
    List<Item> retiring() {
        model.events.add("pending");
        return new ArrayList<Item>(model.rows);
    }
    void validateRetirement(Item proof) throws IOException {
        // This is deliberately a configurable proof gate, NOT a reimplementation of the SQL
        // predicates, transactions, readback receipts, interval membership, or floor validation.
        model.events.add("proof:" + proof.source); model.validatedSources.add(proof.source);
        if (model.proofRejected) throw new IOException("Modeled database proof gate rejected");
    }
    void historyReady() {
        model.events.add("ready"); model.readyCalls++;
        model.meta = new HistoryMeta(model.meta.store, model.meta.generation, true);
    }
    Map<String,Long> floors() throws IOException {
        model.events.add("floors"); model.floorCalls++;
        return new HashMap<String,Long>(model.floors);
    }
    @Override public void close() {
        model.events.add("close"); model.closeCalls++; model.open = false;
    }
}
