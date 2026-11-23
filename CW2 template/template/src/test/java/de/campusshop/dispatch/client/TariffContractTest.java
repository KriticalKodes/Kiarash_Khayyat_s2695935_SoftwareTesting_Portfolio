package de.campusshop.dispatch.client;

import de.campusshop.dispatch.mapper.ManifestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Contract checks for schema drift in the external tariff service. The client must degrade an
 * unknown or malformed tariff to an empty Optional rather than passing null money fields into the
 * calculator. This closes the "subtractive schema change" gap identified in LO3/LO4.
 */
class TariffContractTest {

    private MockRestServiceServer server;
    private RestTariffClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://tariff.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestTariffClient(builder.build(), new ManifestMapper());
    }

    @Test
    void missingRequiredFieldIsRejectedInsteadOfBecomingANullTariff() {
        server.expect(requestTo("http://tariff.test/tariffs/TRF-2"))
                .andRespond(withSuccess("""
                        {
                          "tariffCode": "TRF-2",
                          "serviceName": "Economy",
                          "pricePerKg": 0.80,
                          "fuelSurchargeRate": 0.05,
                          "transitDays": 4
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.findTariff("TRF-2")).isEmpty();
        server.verify();
    }

    @Test
    void wrongFieldTypeIsRejectedInsteadOfPropagatingAnException() {
        server.expect(requestTo("http://tariff.test/tariffs/TRF-3"))
                .andRespond(withSuccess("""
                        {
                          "tariffCode": "TRF-3",
                          "serviceName": "Express",
                          "baseFee": "not-a-number",
                          "pricePerKg": 2.10,
                          "fuelSurchargeRate": 0.05,
                          "transitDays": 1
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.findTariff("TRF-3")).isEmpty();
        server.verify();
    }
}
