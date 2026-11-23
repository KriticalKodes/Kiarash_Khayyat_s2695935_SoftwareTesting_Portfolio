package de.campusshop.dispatch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the whole API in-process with the {@code inmemory} profile (in-memory S3 and DynamoDB
 * replacements) and simulated tariffs, so no Docker is needed.
 */
@SpringBootTest(properties = "tariff-service.mode=simulation")
@ActiveProfiles("inmemory")
@AutoConfigureMockMvc
class ShipmentApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void registerReadListAndDelete() throws Exception {
        String manifestNo = "11111111-1111-4111-8111-111111111111";
        String carrierId = "carrier-integration-register";
        String request = """
                {
                  "manifestNo": "%s",
                  "carrier": { "carrierId": "%s", "name": "Campus Couriers",
                               "contactEmail": "ops@campus-couriers.example" },
                  "dispatchDate": "2026-11-20",
                  "originWarehouse": "EDI-01",
                  "parcels": [ { "tariffCode": "TRF-1", "weightKg": 2.5 },
                               { "tariffCode": "TRF-2", "weightKg": 0.8 } ],
                  "carrierInternalNotes": "must be preserved in the archived manifest"
                }
                """.formatted(manifestNo, carrierId);

        MvcResult created = mvc.perform(post("/dispatch/manifests")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.manifestNo").value(manifestNo))
                .andExpect(jsonPath("$.carrierId").value(carrierId))
                .andExpect(jsonPath("$.state").value("REGISTERED"))
                .andExpect(jsonPath("$.parcelCount").value(2))
                .andExpect(jsonPath("$.totalCharge").exists())
                .andExpect(jsonPath("$.manifestKey").exists())
                .andExpect(jsonPath("$.parcels[0].rating").value("RATED"))
                .andExpect(jsonPath("$.parcels[1].rating").value("RATED"))
                .andReturn();

        String shipmentRef = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("shipmentRef").asText();

        mvc.perform(get("/shipments/{shipmentRef}", shipmentRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentRef").value(shipmentRef))
                .andExpect(jsonPath("$.manifestNo").value(manifestNo));

        // The archive holds the exact bytes the partner sent, including unknown fields.
        mvc.perform(get("/shipments/{shipmentRef}/manifest", shipmentRef))
                .andExpect(status().isOk())
                .andExpect(content().string(request));

        mvc.perform(get("/carriers/{carrierId}/shipments", carrierId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shipmentRef").value(shipmentRef));

        mvc.perform(delete("/shipments/{shipmentRef}", shipmentRef))
                .andExpect(status().isNoContent());

        mvc.perform(get("/shipments/{shipmentRef}", shipmentRef))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_NOT_FOUND"));

        mvc.perform(get("/shipments/{shipmentRef}/manifest", shipmentRef))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidManifestsAreRejected() throws Exception {
        expectBadRequest("""
                { "manifestNo": "invalid-1", "carrier": { "name": "No ID",
                  "contactEmail": "ops@example.org" }, "dispatchDate": "2026-11-20",
                  "parcels": [ { "tariffCode": "TRF-1", "weightKg": 1 } ] }
                """);

        expectBadRequest("""
                { "manifestNo": "invalid-2", "carrier": { "carrierId": "carrier-invalid",
                  "contactEmail": "not-an-email" }, "dispatchDate": "2026-11-20",
                  "parcels": [ { "tariffCode": "TRF-1", "weightKg": 1 } ] }
                """);

        expectBadRequest("""
                { "manifestNo": "invalid-3", "carrier": { "carrierId": "carrier-invalid",
                  "contactEmail": "ops@example.org" }, "dispatchDate": "2026-11-20",
                  "parcels": [] }
                """);

        expectBadRequest("""
                { "manifestNo": "invalid-4", "carrier": { "carrierId": "carrier-invalid",
                  "contactEmail": "ops@example.org" }, "dispatchDate": "2026-11-20",
                  "parcels": [ { "tariffCode": "TRF-1", "weightKg": 0 } ] }
                """);
    }

    @Test
    void duplicateManifestIsAConflict() throws Exception {
        String request = """
                { "manifestNo": "22222222-2222-4222-8222-222222222222",
                  "carrier": { "carrierId": "carrier-integration-duplicate",
                               "contactEmail": "ops@example.org" },
                  "dispatchDate": "2026-11-21",
                  "parcels": [ { "tariffCode": "TRF-1", "weightKg": 1 } ] }
                """;

        mvc.perform(post("/dispatch/manifests").contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isCreated());

        mvc.perform(post("/dispatch/manifests").contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_MANIFEST"));
    }

    private void expectBadRequest(String body) throws Exception {
        mvc.perform(post("/dispatch/manifests").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
