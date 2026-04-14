package io.quarkiverse.ledger.runtime.model;

/** Classifies the actor who produced a {@link LedgerEntry} or {@link LedgerAttestation}. */
public enum ActorType {
    /** A human user acting through a UI or API. */
    HUMAN,
    /** An autonomous AI agent acting programmatically. */
    AGENT,
    /** An automated system process (scheduler, rule engine, etc.). */
    SYSTEM
}
