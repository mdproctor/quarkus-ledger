# Example: Merkle Verification

Demonstrates O(log N) Merkle inclusion proofs using `casehub-ledger`.

## What This Shows

1. Write entries — `digest` and Merkle frontier updated automatically on `repo.save()`
2. Fetch tree root — publishable to an external checkpoint log
3. Generate inclusion proof — O(log N) DB reads
4. Verify independently — no DB access, no trust in operator required

## Run

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl examples/merkle-verification
```

## External Publishing

Configure `quarkus.ledger.merkle.publish.url` and `quarkus.ledger.merkle.publish.private-key`
to POST a signed tlog-checkpoint to your external log on each frontier update.
