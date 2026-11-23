package de.campusshop.dispatch.client;

import de.campusshop.dispatch.mapper.ManifestMapper;
import de.campusshop.dispatch.model.Tariff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the real {@link RestTariffClient} against a mocked HTTP server — no external tariff
 * service is contacted. Covers 200, 404 and 5xx.
 */
class RestTariffClientTest {

    private MockRestServiceServer server;
    private RestTariffClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://tariff.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestTariffClient(builder.build(), new ManifestMapper());
    }

    @Test
    void mapsASuccessfulResponse() {
        server.expect(requestTo("http://tariff.test/tariffs/TRF-1"))
                .andRespond(withSuccess("""
                        {
                          "tariffCode": "TRF-1",
                          "serviceName": "Standard",
                          "baseFee": 4.50,
                          "pricePerKg": 1.20,
                          "fuelSurchargeRate": 0.10,
                          "transitDays": 2
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<Tariff> result = client.findTariff("TRF-1");

        assertThat(result).isPresent();
        Tariff tariff = result.orElseThrow();
        assertThat(tariff.code()).isEqualTo("TRF-1");
        assertThat(tariff.serviceName()).isEqualTo("Standard");
        assertThat(tariff.baseFee()).isEqualByComparingTo("4.50");
        assertThat(tariff.pricePerKg()).isEqualByComparingTo("1.20");
        assertThat(tariff.fuelSurchargeRate()).isEqualByComparingTo("0.10");
        server.verify();
    }

    @Test
    void unknownCodeBecomesEmpty() {
        server.expect(requestTo("http://tariff.test/tariffs/TRF-999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findTariff("TRF-999")).isEmpty();
        server.verify();
    }

    @Test
    void serverErrorBecomesEmptyInsteadOfFailingRegistration() {
        server.expect(requestTo("http://tariff.test/tariffs/TRF-1"))
                .andRespond(withServerError());

        assertThat(client.findTariff("TRF-1")).isEmpty();
        server.verify();
    }
}
