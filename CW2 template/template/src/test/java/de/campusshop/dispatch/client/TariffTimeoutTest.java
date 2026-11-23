package de.campusshop.dispatch.client;

import com.sun.net.httpserver.HttpServer;
import de.campusshop.dispatch.mapper.ManifestMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the configured tariff timeout as behaviour, not just as configuration. A local HTTP
 * server deliberately sleeps longer than the client read timeout; the client must give up, return
 * an empty Optional and do so quickly enough to protect the registration path.
 */
class TariffTimeoutTest {

    @Test
    void slowTariffServiceTimesOutAndDegradesToEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tariffs", exchange -> {
            try {
                Thread.sleep(600);
                byte[] body = """
                        {"tariffCode":"TRF-SLOW","serviceName":"Slow","baseFee":1.0,
                         "pricePerKg":1.0,"fuelSurchargeRate":0.0,"transitDays":1}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(100);
            factory.setReadTimeout(80);
            RestClient client = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(factory)
                    .build();
            RestTariffClient tariffClient = new RestTariffClient(client, new ManifestMapper());

            long started = System.nanoTime();
            assertThat(tariffClient.findTariff("TRF-SLOW")).isEmpty();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(elapsedMs).isLessThan(500)
                    .as("timeout must bound the tariff call, observed duration was %d ms", elapsedMs);
        } finally {
            server.stop(0);
        }
    }
}
