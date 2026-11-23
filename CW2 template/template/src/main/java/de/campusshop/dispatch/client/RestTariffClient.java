package de.campusshop.dispatch.client;

import de.campusshop.dispatch.dto.TariffResponse;
import de.campusshop.dispatch.mapper.ManifestMapper;
import de.campusshop.dispatch.model.Tariff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Calls the external tariff service ({@code GET {url}/tariffs/{tariffCode}}).
 *
 * <p>Base URL and timeouts are already set on the injected {@link RestClient} in
 * {@link de.campusshop.dispatch.config.TariffClientConfig}.
 */
@Component
@ConditionalOnProperty(name = "tariff-service.mode", havingValue = "rest", matchIfMissing = true)
public class RestTariffClient implements TariffClient {

    private static final Logger log = LoggerFactory.getLogger(RestTariffClient.class);

    private final RestClient restClient;
    private final ManifestMapper mapper;

    public RestTariffClient(RestClient tariffRestClient, ManifestMapper mapper) {
        this.restClient = tariffRestClient;
        this.mapper = mapper;
    }

    /**
     * Calls the tariff service and maps the JSON to the internal model. A {@code 404}, a timeout or
     * any other failure must end as an empty result, never as an exception.
     */
    @Override
    public Optional<Tariff> findTariff(String tariffCode) {
        long started = System.nanoTime();
        try {
            TariffResponse response = restClient.get()
                    .uri("/tariffs/{tariffCode}", tariffCode)
                    .retrieve()
                    .body(TariffResponse.class);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            if (response == null || !hasRequiredFields(response)) {
                log.warn("Tariff response rejected for tariffCode={} durationMs={}", tariffCode, durationMs);
                return Optional.empty();
            }
            log.debug("Tariff lookup succeeded tariffCode={} durationMs={}", tariffCode, durationMs);
            return Optional.of(mapper.toTariff(response));
        } catch (RestClientException | HttpMessageConversionException ex) {
            // 404, timeout, connection problem or malformed response: an unknown tariff must not
            // fail the whole registration — the parcel is simply left UNRATED. Programming errors
            // are no longer swallowed here.
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.warn("Tariff lookup failed for tariffCode={} durationMs={}: {}",
                    tariffCode, durationMs, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Rejects subtractive or malformed schema drift before it can reach the calculator. */
    private boolean hasRequiredFields(TariffResponse response) {
        return response.tariffCode() != null && !response.tariffCode().isBlank()
                && response.serviceName() != null && !response.serviceName().isBlank()
                && response.baseFee() != null
                && response.pricePerKg() != null
                && response.fuelSurchargeRate() != null;
    }
}
