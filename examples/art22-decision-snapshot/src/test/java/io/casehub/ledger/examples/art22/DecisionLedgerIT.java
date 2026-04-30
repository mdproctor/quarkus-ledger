package io.casehub.ledger.examples.art22;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DecisionLedgerIT {

    private static final String BASE = "/decisions";

    @Test
    void recordDecision_returns201_withArt22Fields() {
        final String subject = UUID.randomUUID().toString();
        given()
                .contentType("application/json")
                .body(request(subject, "credit-risk", "APPROVED", "risk-model-v3", 0.88))
                .when().post(BASE)
                .then()
                .statusCode(201)
                .body("art22.algorithmRef", equalTo("risk-model-v3"))
                .body("art22.confidenceScore", equalTo(0.88f))
                .body("art22.humanOverrideAvailable", equalTo(true))
                .body("art22.contestationUri", containsString("challenge"));
    }

    @Test
    void ledgerHistory_containsSupplementJson() {
        final String subject = UUID.randomUUID().toString();
        given().contentType("application/json")
                .body(request(subject, "content-moderation", "FLAGGED", "safety-classifier-v2", 0.95))
                .when().post(BASE).then().statusCode(201);

        given()
                .when().get(BASE + "/" + subject + "/ledger")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].supplementJson", containsString("COMPLIANCE"))
                .body("[0].supplementJson", containsString("safety-classifier-v2"));
    }

    @Test
    void hashChain_intactAfterTwoDecisions() {
        final String subject = UUID.randomUUID().toString();
        given().contentType("application/json")
                .body(request(subject, "fraud-detection", "APPROVED", "fraud-model-v1", 0.72))
                .when().post(BASE).then().statusCode(201);
        given().contentType("application/json")
                .body(request(subject, "fraud-detection", "FLAGGED", "fraud-model-v1", 0.91))
                .when().post(BASE).then().statusCode(201);

        given()
                .when().get(BASE + "/" + subject + "/ledger/verify")
                .then()
                .statusCode(200)
                .body("intact", equalTo(true))
                .body("entries", equalTo(2));
    }

    private String request(final String subjectId, final String category,
            final String outcome, final String algorithmRef, final double confidence) {
        return String.format(
                "{\"subjectId\":\"%s\",\"category\":\"%s\",\"outcome\":\"%s\","
                        + "\"algorithmRef\":\"%s\",\"confidence\":%s,"
                        + "\"inputContext\":\"{\\\"inputs\\\":{\\\"score\\\":%s}}\"}",
                subjectId, category, outcome, algorithmRef, confidence, (int) (confidence * 100));
    }
}
