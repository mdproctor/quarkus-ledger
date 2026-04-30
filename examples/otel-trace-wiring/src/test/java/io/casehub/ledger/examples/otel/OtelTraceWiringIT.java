package io.casehub.ledger.examples.otel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;

/**
 * Verifies that {@code LedgerEntry.traceId} is automatically populated from the active
 * OpenTelemetry span when a ledger entry is persisted inside an HTTP request.
 *
 * <p>
 * Quarkus OTel creates a server span for every inbound HTTP request. {@code LedgerTraceListener}
 * reads {@code Span.current()} at JPA persist time and sets {@code traceId} — no call-site
 * code is needed. These tests verify the end-to-end wiring by making HTTP calls and
 * inspecting the {@code traceId} returned in the response (which is read directly from the
 * persisted entity field).
 *
 * <p>
 * The OTLP exporter endpoint is unreachable in test mode, but that only affects export —
 * spans are still created and are visible via {@code Span.current()} during the request.
 */
@QuarkusTest
class OtelTraceWiringIT {

    // -------------------------------------------------------------------------
    // Existing baseline tests
    // -------------------------------------------------------------------------

    @Test
    void recordEvent_traceId_isAutoPopulated() {
        // POST via HTTP — Quarkus OTel creates a server span automatically for this request.
        // LedgerTraceListener reads the span at persist time and populates traceId.
        String traceId = given()
                .contentType("application/json")
                .body("{\"name\":\"UserLoggedIn\",\"actorId\":\"alice\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("traceId");

        // traceId must be a valid 32-char hex OTel trace ID
        assertThat(traceId).isNotNull();
        assertThat(traceId).hasSize(32);
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void recordEvent_traceId_differentForEachRequest() {
        // Two events in separate requests must have different trace IDs —
        // each HTTP request creates an independent OTel server span.
        String traceId1 = given()
                .contentType("application/json")
                .body("{\"name\":\"EventA\",\"actorId\":\"bob\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("traceId");

        String traceId2 = given()
                .contentType("application/json")
                .body("{\"name\":\"EventB\",\"actorId\":\"carol\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("traceId");

        assertThat(traceId1).isNotNull().hasSize(32).matches("[0-9a-f]{32}");
        assertThat(traceId2).isNotNull().hasSize(32).matches("[0-9a-f]{32}");
        assertThat(traceId1).isNotEqualTo(traceId2);
    }

    @Test
    void getEvent_traceId_persistedCorrectly() {
        // POST to create, then GET by ID — verifies that the traceId survives the
        // round-trip to the database and can be retrieved independently of the write.
        String id = given()
                .contentType("application/json")
                .body("{\"name\":\"EventC\",\"actorId\":\"dave\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("id");

        String traceId = given()
                .when().get("/events/" + id)
                .then().statusCode(200)
                .extract().path("traceId");

        assertThat(traceId).isNotNull().hasSize(32).matches("[0-9a-f]{32}");
    }

    // -------------------------------------------------------------------------
    // Correctness tests
    // -------------------------------------------------------------------------

    @Test
    void traceId_isPresent_inLedgerEntry() {
        // POST an event, then GET the entry by id — traceId field must not be null or empty.
        String id = given()
                .contentType("application/json")
                .body("{\"name\":\"LoginAttempt\",\"actorId\":\"eve\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("id");

        String traceId = given()
                .when().get("/events/" + id)
                .then().statusCode(200)
                .extract().path("traceId");

        assertThat(traceId).isNotNull().isNotEmpty();
    }

    @Test
    void traceId_is32LowercaseHexChars() {
        // The OTel trace ID wire format is exactly 32 lowercase hex characters.
        String traceId = given()
                .contentType("application/json")
                .body("{\"name\":\"DataExport\",\"actorId\":\"frank\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("traceId");

        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void traceId_appearsInRestResponse_andPersistsToDb() {
        // The traceId from the 201 POST response must exactly equal the traceId
        // returned by the subsequent GET — proving the value is persisted, not computed on-the-fly.
        ValidatableResponse postResponse = given()
                .contentType("application/json")
                .body("{\"name\":\"AuditEvent\",\"actorId\":\"grace\"}")
                .when().post("/events")
                .then().statusCode(201);

        String postId = postResponse.extract().path("id");
        String postTraceId = postResponse.extract().path("traceId");

        String getTraceId = given()
                .when().get("/events/" + postId)
                .then().statusCode(200)
                .extract().path("traceId");

        assertThat(postTraceId).isNotNull().matches("[0-9a-f]{32}");
        assertThat(getTraceId).isEqualTo(postTraceId);
    }

    // -------------------------------------------------------------------------
    // Happy-path tests
    // -------------------------------------------------------------------------

    @Test
    void multipleEvents_eachHasNonNullTraceId() {
        // Three sequential POST calls — each must carry a non-null traceId.
        String[] bodies = {
                "{\"name\":\"OrderPlaced\",\"actorId\":\"henry\"}",
                "{\"name\":\"OrderShipped\",\"actorId\":\"henry\"}",
                "{\"name\":\"OrderDelivered\",\"actorId\":\"henry\"}"
        };

        for (String body : bodies) {
            String traceId = given()
                    .contentType("application/json")
                    .body(body)
                    .when().post("/events")
                    .then().statusCode(201)
                    .extract().path("traceId");

            assertThat(traceId).isNotNull().isNotEmpty();
        }
    }

    @Test
    void differentRequests_produceDifferentTraceIds() {
        // Each HTTP request creates its own OTel server span, so trace IDs must differ.
        String traceIdA = given()
                .contentType("application/json")
                .body("{\"name\":\"SignupStarted\",\"actorId\":\"iris\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("traceId");

        String traceIdB = given()
                .contentType("application/json")
                .body("{\"name\":\"SignupCompleted\",\"actorId\":\"jack\"}")
                .when().post("/events")
                .then().statusCode(201)
                .extract().path("traceId");

        assertThat(traceIdA).isNotNull().matches("[0-9a-f]{32}");
        assertThat(traceIdB).isNotNull().matches("[0-9a-f]{32}");
        assertThat(traceIdA).isNotEqualTo(traceIdB);
    }

    // -------------------------------------------------------------------------
    // Robustness tests
    // -------------------------------------------------------------------------

    @Test
    void getEvent_nonexistentId_returns404() {
        // A random UUID that was never persisted must yield 404 — not a 500 or silent empty body.
        given()
                .when().get("/events/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void postEvent_missingBody_returns400OrSimilar() {
        // A POST with no body (or empty body) is malformed — expect a 4xx error response.
        int status = given()
                .contentType("application/json")
                .when().post("/events")
                .then().extract().statusCode();

        assertThat(status).isBetween(400, 499);
    }
}
