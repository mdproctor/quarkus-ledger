package io.quarkiverse.ledger.examples.order;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the order-processing example.
 * Run with: mvn test (inside examples/order-processing/)
 */
@QuarkusTest
class OrderLedgerIT {

    private static final String BASE = "/orders";

    @Test
    void placeOrder_returns201_withOrderId() {
        given()
                .contentType("application/json")
                .body("{\"customerId\":\"it-alice-1\",\"total\":99.99}")
                .when().post(BASE)
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("PLACED"))
                .body("customerId", equalTo("it-alice-1"));
    }

    @Test
    void placeOrder_createsOneLedgerEntry() {
        final String orderId = placeOrder("it-alice-2", "49.00");

        given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].commandType", equalTo("PlaceOrder"))
                .body("[0].eventType", equalTo("OrderPlaced"))
                .body("[0].actorId", equalTo("it-alice-2"))
                .body("[0].sequenceNumber", equalTo(1))
                .body("[0].digest", notNullValue());
    }

    @Test
    void shipOrder_addsSecondLedgerEntry() {
        final String orderId = placeOrder("it-bob-1", "75.00");
        given().when().put(BASE + "/" + orderId + "/ship?actor=warehouse").then().statusCode(200);

        given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[1].commandType", equalTo("ShipOrder"))
                .body("[1].eventType", equalTo("OrderShipped"))
                .body("[1].sequenceNumber", equalTo(2));
    }

    @Test
    void fullLifecycle_threeEntries_chainIsValid() {
        final String orderId = placeOrder("it-carol-1", "199.99");
        given().when().put(BASE + "/" + orderId + "/ship?actor=warehouse").then().statusCode(200);
        given().when().put(BASE + "/" + orderId + "/deliver?actor=courier").then().statusCode(200);

        given()
                .when().get(BASE + "/" + orderId + "/ledger/verify")
                .then()
                .statusCode(200)
                .body("intact", equalTo(true))
                .body("entries", equalTo(3));
    }

    @Test
    void hashChain_secondEntryLinkedToFirst() {
        final String orderId = placeOrder("it-dave-1", "30.00");
        given().when().put(BASE + "/" + orderId + "/ship?actor=warehouse").then().statusCode(200);

        final List<String> digests = given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then().statusCode(200)
                .extract().jsonPath().getList("digest");
        final List<String> prevHashes = given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then().statusCode(200)
                .extract().jsonPath().getList("previousHash");

        assertThat(prevHashes.get(0)).isNull();
        assertThat(prevHashes.get(1)).isEqualTo(digests.get(0));
    }

    @Test
    void ledgerEntry_hasDecisionContext() {
        final String orderId = placeOrder("it-eve-1", "55.00");

        given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].supplementJson", notNullValue())
                .body("[0].supplementJson", containsString("PLACED"));
    }

    @Test
    void cancelOrder_recordsRationale() {
        final String orderId = placeOrder("it-frank-1", "10.00");
        given().when()
                .given().queryParam("actor", "it-frank-1").queryParam("reason", "Changed mind").when().put(BASE + "/" + orderId + "/cancel")
                .then().statusCode(200);

        given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then()
                .statusCode(200)
                .body("[1].commandType", equalTo("CancelOrder"))
                .body("[1].supplementJson", containsString("Changed mind"));
    }

    @Test
    void attestation_appearsInLedger() {
        final String orderId = placeOrder("it-grace-1", "200.00");
        final String entryId = given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then().statusCode(200)
                .extract().jsonPath().getString("[0].id");

        given()
                .contentType("application/json")
                .body("{\"attestorId\":\"compliance-bot\",\"attestorType\":\"AGENT\","
                        + "\"verdict\":\"SOUND\",\"confidence\":0.95}")
                .when().post(BASE + "/" + orderId + "/ledger/" + entryId + "/attestations")
                .then().statusCode(201);

        given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].attestations", hasSize(1))
                .body("[0].attestations[0].attestorId", equalTo("compliance-bot"));
    }

    @Test
    void placeOrder_supplementJson_containsDecisionContext() {
        final String orderId = placeOrder("it-supp-1", "99.00");

        given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].supplementJson", notNullValue())
                .body("[0].supplementJson", containsString("decisionContext"))
                .body("[0].supplementJson", containsString("PLACED"));
    }

    @Test
    void cancelOrder_supplementJson_containsRationale() {
        final String orderId = placeOrder("it-supp-2", "25.00");
        given().queryParam("actor", "it-supp-2").queryParam("reason", "No longer needed")
                .when().put(BASE + "/" + orderId + "/cancel")
                .then().statusCode(200);

        given()
                .when().get(BASE + "/" + orderId + "/ledger")
                .then()
                .statusCode(200)
                .body("[1].supplementJson", containsString("No longer needed"));
    }

    @Test
    void findByActorId_returnsAllOrdersForActor() {
        final String actor = "it-audit-actor-1";
        placeOrder(actor, "10.00");
        placeOrder(actor, "20.00");
        placeOrder(actor, "30.00");
        placeOrder("it-audit-actor-2", "99.00");

        given()
                .when().get(BASE + "/audit?actorId=" + actor
                        + "&from=2020-01-01T00:00:00Z&to=2099-12-31T23:59:59Z")
                .then()
                .statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(3)))
                .body("actorId", everyItem(equalTo(actor)));
    }

    private String placeOrder(final String customerId, final String total) {
        return given()
                .contentType("application/json")
                .body(String.format("{\"customerId\":\"%s\",\"total\":%s}", customerId, total))
                .when().post(BASE)
                .then().statusCode(201)
                .extract().path("id");
    }
}
