# Example: GDPR Art.22 Decision Snapshot

This example demonstrates how to use `casehub-ledger` with `ComplianceSupplement`
to build an AI decision service that is compliant with GDPR Article 22.

## What is GDPR Article 22?

Article 22 of the GDPR gives individuals the right not to be subject to decisions
based solely on automated processing when those decisions have legal or similarly
significant effects. When organisations do make such decisions, Article 22(2) and
recital 71 require:

- **Explainability** — meaningful information about the logic involved
- **Human oversight** — the ability to obtain human intervention
- **Contestation** — the right to express one's view and challenge the decision

## How this example satisfies Article 22

Each AI decision records a `ComplianceSupplement` with four structured fields:

| Field | Art.22 requirement satisfied |
|---|---|
| `algorithmRef` | "Meaningful information about the logic involved" — identifies which model or rule produced the decision |
| `confidenceScore` | "Significance and envisaged consequences" — the model's stated certainty |
| `contestationUri` | Right to contest — a URI where the subject can request human review |
| `humanOverrideAvailable` | Right to obtain human intervention — explicit boolean flag |

The `decisionContext` field in the same supplement carries a full JSON snapshot of
the inputs used — satisfying Arts.13–15's right to receive information about
"the categories of personal data used".

## Running the example

```bash
cd examples/art22-decision-snapshot
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev
```

```bash
# Record an AI decision
curl -X POST http://localhost:8080/decisions \
  -H "Content-Type: application/json" \
  -d '{
    "subjectId": "customer-uuid-here",
    "category": "credit-risk",
    "outcome": "APPROVED",
    "algorithmRef": "risk-model-v3",
    "confidence": 0.88,
    "inputContext": "{\"creditScore\":720}"
  }'

# Retrieve full decision history with Art.22 fields
curl http://localhost:8080/decisions/customer-uuid-here/ledger

# Verify hash chain integrity
curl http://localhost:8080/decisions/customer-uuid-here/ledger/verify
```

## Running the tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test
```
