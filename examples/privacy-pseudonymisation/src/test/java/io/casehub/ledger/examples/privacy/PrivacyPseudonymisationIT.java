package io.casehub.ledger.examples.privacy;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.examples.privacy.service.CreditApplicationService;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.casehub.ledger.runtime.privacy.LedgerErasureService.ErasureResult;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the GDPR privacy lifecycle:
 * pseudonymisation on write, supplement fields, and Art.17 erasure.
 */
@QuarkusTest
class PrivacyPseudonymisationIT {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Inject
    CreditApplicationService service;

    @Inject
    LedgerEntryRepository repo;

    @Inject
    EntityManager em;

    // ── Happy path: actorId is pseudonymised on write ─────────────────────────

    /**
     * When tokenisation is enabled, the human officer's raw email is replaced with a UUID
     * token on save. The raw identity must not be present anywhere in the stored entry.
     */
    @Test
    @Transactional
    void humanActorId_isPseudonymisedOnWrite() {
        final UUID applicationId = UUID.randomUUID();
        final String officerId = "alice-" + UUID.randomUUID() + "@example.com";

        service.humanReview(applicationId, officerId, true);

        final List<LedgerEntry> entries = repo.findBySubjectId(applicationId);
        assertThat(entries).hasSize(1);

        final LedgerEntry entry = entries.get(0);
        assertThat(entry.actorId)
                .as("actorId must be a UUID token, not the raw email")
                .isNotEqualTo(officerId);
        assertThat(UUID_PATTERN.matcher(entry.actorId).matches())
                .as("actorId must match UUID format after pseudonymisation")
                .isTrue();
    }

    // ── Happy path: compliance supplement detail field present ────────────────

    /**
     * The ComplianceSupplement.detail field must be populated and surfaced in supplementJson.
     * This is the free-text explanation of the scoring logic — often omitted in other examples.
     */
    @Test
    @Transactional
    void complianceSupplement_detailFieldPresent() {
        final UUID applicationId = UUID.randomUUID();

        service.analyseApplication(applicationId, "test-applicant", 0.5);

        final List<LedgerEntry> entries = repo.findBySubjectId(applicationId);
        assertThat(entries).hasSize(1);

        final LedgerEntry entry = entries.get(0);

        // supplementJson is the denormalised snapshot — check it contains the detail text
        assertThat(entry.supplementJson)
                .as("supplementJson must contain the detail explanation")
                .contains("Risk score computed from income");

        // Also verify via the typed accessor
        assertThat(entry.compliance())
                .isPresent()
                .hasValueSatisfying(cs -> {
                    assertThat(cs.detail)
                            .as("ComplianceSupplement.detail must be set")
                            .startsWith("Risk score computed from income");
                    assertThat(cs.algorithmRef).isEqualTo("credit-risk-model-v3");
                    assertThat(cs.humanOverrideAvailable).isTrue();
                });
    }

    // ── Happy path: agentConfigHash in provenance supplement ──────────────────

    /**
     * The ProvenanceSupplement.agentConfigHash field must be populated for AI agent entries.
     * This enables forensic detection of LLM configuration drift within a persona version.
     */
    @Test
    @Transactional
    void provenanceSupplement_agentConfigHashPresent() {
        final UUID applicationId = UUID.randomUUID();

        service.analyseApplication(applicationId, "test-applicant", 0.4);

        final List<LedgerEntry> entries = repo.findBySubjectId(applicationId);
        assertThat(entries).hasSize(1);

        final LedgerEntry entry = entries.get(0);

        assertThat(entry.provenance())
                .as("ProvenanceSupplement must be attached to AI agent entries")
                .isPresent()
                .hasValueSatisfying(ps -> {
                    assertThat(ps.agentConfigHash)
                            .as("agentConfigHash must be a 64-char SHA-256 hex digest")
                            .hasSize(64)
                            .matches("[0-9a-f]{64}");
                    assertThat(ps.sourceEntitySystem).isEqualTo("credit-risk-platform");
                    assertThat(ps.sourceEntityType).isEqualTo("CreditApplication");
                });
    }

    // ── Happy path: erasure severs identity mapping ───────────────────────────

    /**
     * After a GDPR Art.17 erasure request:
     * - mappingFound must be true (the identity existed before erasure)
     * - the actorId token remains in the ledger entry (audit record survives)
     * - the token→identity mapping is deleted (actorId is now permanently unresolvable)
     */
    @Test
    @Transactional
    void erasure_seversIdentityMapping() {
        final UUID applicationId = UUID.randomUUID();
        final String officerId = "bob-" + UUID.randomUUID() + "@example.com";

        service.humanReview(applicationId, officerId, false);

        // Capture the token before erasure
        final LedgerEntry entryBeforeErasure = repo.findBySubjectId(applicationId).get(0);
        final String tokenBeforeErasure = entryBeforeErasure.actorId;
        assertThat(tokenBeforeErasure).isNotEqualTo(officerId);

        // Perform erasure — this must be in its own transaction in practice;
        // in the test we flush first to ensure the mapping row is visible
        em.flush();
        final ErasureResult result = service.erase(officerId);

        assertThat(result.mappingFound())
                .as("erasure must find and sever the mapping for a known actor")
                .isTrue();
        assertThat(result.rawActorId()).isEqualTo(officerId);
        assertThat(result.affectedEntryCount()).isGreaterThanOrEqualTo(1L);

        // The token remains on the ledger entry — the audit record is intact
        em.clear(); // bypass L1 cache
        final LedgerEntry entryAfterErasure = repo.findBySubjectId(applicationId).get(0);
        assertThat(entryAfterErasure.actorId)
                .as("token must remain on the ledger entry after erasure — audit record survives")
                .isEqualTo(tokenBeforeErasure);
    }

    // ── Correctness: AI agent actorId is also pseudonymised ───────────────────

    /**
     * AI agent persona names ("claude:risk-agent@v1") are pseudonymised exactly like
     * human identities. The stored actorId must be a UUID token, never the raw persona name.
     */
    @Test
    @Transactional
    void agentActorId_isPseudonymisedOnWrite() {
        final UUID applicationId = UUID.randomUUID();

        service.analyseApplication(applicationId, "any-applicant", 0.6);

        final List<LedgerEntry> entries = repo.findBySubjectId(applicationId);
        assertThat(entries).hasSize(1);

        final LedgerEntry entry = entries.get(0);
        assertThat(entry.actorId)
                .as("agent persona name must be replaced with a UUID token")
                .isNotEqualTo("claude:risk-agent@v1");
        assertThat(UUID_PATTERN.matcher(entry.actorId).matches())
                .as("agent actorId must match UUID format after pseudonymisation")
                .isTrue();
    }

    // ── Correctness: erasure endpoint reachable via REST ─────────────────────

    /**
     * Smoke-test the REST erasure endpoint for a previously-written actor.
     */
    @Test
    void erasureEndpoint_returnsErasureResult() {
        final UUID applicationId = UUID.randomUUID();
        final String officerId = "carol-" + UUID.randomUUID() + "@example.com";

        writeReview(applicationId, officerId);

        given()
                .when().post("/applications/erasure/{actorId}", officerId)
                .then()
                .statusCode(200)
                .body("rawActorId", org.hamcrest.Matchers.equalTo(officerId))
                .body("mappingFound", org.hamcrest.Matchers.equalTo(true));
    }

    // ── Correctness: ledger endpoint returns entries ───────────────────────────

    @Test
    void ledgerEndpoint_returnsEntriesForApplication() {
        final UUID applicationId = UUID.randomUUID();

        writeAnalysis(applicationId);

        given()
                .when().get("/applications/{id}/ledger", applicationId)
                .then()
                .statusCode(200)
                .body("$", org.hamcrest.Matchers.hasSize(1));
    }

    // ── Happy path: erasure affectedEntryCount matches written entries ────────

    /**
     * Writing two entries for the same officer and then erasing must report
     * affectedEntryCount == 2 — the count must reflect all ledger rows whose
     * actorId matched the erased token, not just the first one.
     */
    @Test
    @Transactional
    void erasure_affectedEntryCount_matchesWrittenEntries() {
        final UUID appId1 = UUID.randomUUID();
        final UUID appId2 = UUID.randomUUID();
        final String officerId = "dana-" + UUID.randomUUID() + "@example.com";

        service.humanReview(appId1, officerId, true);
        service.humanReview(appId2, officerId, false);

        em.flush();
        final ErasureResult result = service.erase(officerId);

        assertThat(result.mappingFound()).isTrue();
        assertThat(result.affectedEntryCount())
                .as("affectedEntryCount must equal the number of entries written for this officer")
                .isEqualTo(2L);
    }

    // ── Happy path: all provenance source fields present ─────────────────────

    /**
     * The ProvenanceSupplement attached to an analysis entry must carry all three
     * source fields: sourceEntityId, sourceEntityType, and sourceEntitySystem.
     * Verifies via supplementJson (the denormalised snapshot stored on the entry).
     */
    @Test
    @Transactional
    void provenanceSupplement_allSourceFields_present() {
        final UUID applicationId = UUID.randomUUID();

        service.analyseApplication(applicationId, "test-applicant", 0.3);

        final LedgerEntry entry = repo.findBySubjectId(applicationId).get(0);

        assertThat(entry.supplementJson)
                .as("supplementJson must contain sourceEntityId")
                .contains("sourceEntityId")
                .as("supplementJson must contain sourceEntityType")
                .contains("sourceEntityType")
                .as("supplementJson must contain sourceEntitySystem")
                .contains("sourceEntitySystem");

        assertThat(entry.provenance())
                .isPresent()
                .hasValueSatisfying(ps -> {
                    assertThat(ps.sourceEntityId).isEqualTo(applicationId.toString());
                    assertThat(ps.sourceEntityType).isEqualTo("CreditApplication");
                    assertThat(ps.sourceEntitySystem).isEqualTo("credit-risk-platform");
                });
    }

    // ── Happy path: contestationUri contains the entry UUID ──────────────────

    /**
     * The ComplianceSupplement.contestationUri must contain the applicationId so that
     * the challenge URL can be dereferenced back to the specific decision.
     */
    @Test
    @Transactional
    void complianceSupplement_contestationUri_containsEntryId() {
        final UUID applicationId = UUID.randomUUID();

        service.analyseApplication(applicationId, "test-applicant", 0.5);

        final LedgerEntry entry = repo.findBySubjectId(applicationId).get(0);

        assertThat(entry.compliance())
                .isPresent()
                .hasValueSatisfying(cs -> assertThat(cs.contestationUri)
                        .as("contestationUri must contain the applicationId")
                        .contains(applicationId.toString()));
    }

    // ── Correctness: pseudonymised token is a valid UUID ─────────────────────

    /**
     * The token written to actorId after pseudonymisation must be a well-formed UUID
     * (lower-case hex with standard hyphen placement). This validates the tokenisation
     * format contract beyond just "not equal to the raw value".
     */
    @Test
    @Transactional
    void pseudonymisedToken_isValidUUID() {
        final UUID applicationId = UUID.randomUUID();
        final String officerId = "eve-" + UUID.randomUUID() + "@example.com";

        service.humanReview(applicationId, officerId, true);

        final LedgerEntry entry = repo.findBySubjectId(applicationId).get(0);

        assertThat(entry.actorId)
                .as("pseudonymised actorId must match UUID regex")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // ── Correctness: human review compliance supplement rationale present ─────

    /**
     * The ComplianceSupplement attached to an approved human review must carry the
     * rationale text. Verifies via supplementJson to confirm the denormalised snapshot
     * is persisted correctly.
     */
    @Test
    @Transactional
    void humanReview_complianceSupplement_rationalePresent() {
        final UUID applicationId = UUID.randomUUID();
        final String officerId = "frank-" + UUID.randomUUID() + "@example.com";

        service.humanReview(applicationId, officerId, true);

        final LedgerEntry entry = repo.findBySubjectId(applicationId).get(0);

        assertThat(entry.supplementJson)
                .as("supplementJson must contain the rationale text for an approved review")
                .contains("Risk assessment reviewed and accepted.");

        assertThat(entry.compliance())
                .isPresent()
                .hasValueSatisfying(cs -> assertThat(cs.rationale)
                        .isEqualTo("Risk assessment reviewed and accepted."));
    }

    // ── Robustness: erasure of unknown actor returns mappingFound=false ───────

    /**
     * Erasing an actor who never wrote any ledger entries (no token→identity mapping
     * exists) must return mappingFound=false. The service must not throw or fabricate
     * a result — it must report cleanly that nothing was found.
     */
    @Test
    void erasure_unknownActor_mappingNotFound() {
        final String unknownActor = "never-existed-" + UUID.randomUUID() + "@example.com";

        given()
                .when().post("/applications/erasure/{actorId}", unknownActor)
                .then()
                .statusCode(200)
                .body("mappingFound", org.hamcrest.Matchers.equalTo(false))
                .body("affectedEntryCount", org.hamcrest.Matchers.equalTo(0));
    }

    // ── Robustness: erasure is idempotent ─────────────────────────────────────

    /**
     * Erasing the same actor twice must not throw on the second call. The second
     * erasure finds no mapping and must return mappingFound=false with count 0.
     * This validates the idempotency guarantee required for reliable GDPR processing.
     */
    @Test
    void erasure_idempotent_secondCall_mappingNotFound() {
        final UUID applicationId = UUID.randomUUID();
        final String officerId = "grace-" + UUID.randomUUID() + "@example.com";

        writeReview(applicationId, officerId);

        // First erasure — must succeed
        given()
                .when().post("/applications/erasure/{actorId}", officerId)
                .then()
                .statusCode(200)
                .body("mappingFound", org.hamcrest.Matchers.equalTo(true));

        // Second erasure — mapping is already gone
        given()
                .when().post("/applications/erasure/{actorId}", officerId)
                .then()
                .statusCode(200)
                .body("mappingFound", org.hamcrest.Matchers.equalTo(false))
                .body("affectedEntryCount", org.hamcrest.Matchers.equalTo(0));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    @Transactional
    void writeAnalysis(final UUID applicationId) {
        service.analyseApplication(applicationId, "fixture-applicant", 0.5);
    }

    @Transactional
    void writeReview(final UUID applicationId, final String officerId) {
        service.humanReview(applicationId, officerId, true);
    }
}
