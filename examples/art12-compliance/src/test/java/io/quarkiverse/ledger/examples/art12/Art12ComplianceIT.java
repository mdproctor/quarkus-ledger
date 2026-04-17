package io.quarkiverse.ledger.examples.art12;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the art12-compliance example.
 * Run with: mvn test (inside examples/art12-compliance/)
 */
@QuarkusTest
class Art12ComplianceIT {

    @Test
    void recordDecision_returns201() {
        given()
                .queryParam("actorId", "agent-1")
                .queryParam("category", "credit-risk")
                .queryParam("algorithm", "model-v3")
                .queryParam("confidence", "0.92")
                .when().post("/decisions")
                .then().statusCode(201)
                .body("actorId", equalTo("agent-1"));
    }

    @Test
    void auditByActor_returnsDecisions() {
        final String actor = "audit-agent-" + UUID.randomUUID();
        given().queryParam("actorId", actor)
                .queryParam("category", "fraud")
                .queryParam("algorithm", "v1")
                .when().post("/decisions").then().statusCode(201);

        given()
                .queryParam("actorId", actor)
                .queryParam("from", "2020-01-01T00:00:00Z")
                .queryParam("to", "2099-12-31T23:59:59Z")
                .when().get("/decisions/audit")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].actorId", equalTo(actor))
                .body("[0].algorithmRef", equalTo("v1"));
    }

    @Test
    void auditByRange_returnsDecisionsInWindow() {
        given().queryParam("actorId", "range-agent")
                .queryParam("category", "content-mod")
                .queryParam("algorithm", "safety-v2")
                .when().post("/decisions").then().statusCode(201);

        given()
                .queryParam("from", "2020-01-01T00:00:00Z")
                .queryParam("to", "2099-12-31T23:59:59Z")
                .when().get("/decisions/range")
                .then().statusCode(200)
                .body("$", not(empty()));
    }
}
