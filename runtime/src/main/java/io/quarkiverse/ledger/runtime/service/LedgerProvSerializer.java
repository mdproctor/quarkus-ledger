package io.quarkiverse.ledger.runtime.service;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

public final class LedgerProvSerializer {
    private LedgerProvSerializer() {
    }

    public static String toProvJsonLd(UUID subjectId, List<LedgerEntry> entries) {
        throw new UnsupportedOperationException();
    }
}
