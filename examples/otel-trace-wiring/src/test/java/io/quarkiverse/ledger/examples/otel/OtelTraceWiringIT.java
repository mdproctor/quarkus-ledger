package io.quarkiverse.ledger.examples.otel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

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
}
