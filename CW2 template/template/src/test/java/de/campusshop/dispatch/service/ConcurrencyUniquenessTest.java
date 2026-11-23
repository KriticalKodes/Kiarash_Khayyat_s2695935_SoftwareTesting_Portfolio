package de.campusshop.dispatch.service;

import de.campusshop.dispatch.client.TariffClient;
import de.campusshop.dispatch.dto.CarrierDto;
import de.campusshop.dispatch.dto.DispatchManifest;
import de.campusshop.dispatch.dto.ParcelDto;
import de.campusshop.dispatch.exception.DuplicateManifestException;
import de.campusshop.dispatch.mapper.ManifestMapper;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.model.Tariff;
import de.campusshop.dispatch.repository.ManifestArchive;
import de.campusshop.dispatch.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency check for R2. Two simultaneous registrations of the same manifest number must produce
 * exactly one successful registration and one DuplicateManifestException. This exercises the new
 * in-process per-manifest lock and closes the "no concurrency test" gap from LO4.
 */
class ConcurrencyUniquenessTest {

    @Test
    void parallelPostsOfTheSameManifestRegisterExactlyOnce() throws Exception {
        FakeShipmentRepository shipments = new FakeShipmentRepository();
        FakeManifestArchive archive = new FakeManifestArchive();
        Tariff tariff = new Tariff("TRF-1", "Standard",
                new BigDecimal("4.50"), new BigDecimal("1.20"), new BigDecimal("0.10"));
        TariffClient tariffClient = code -> Optional.of(tariff);
        ShipmentRegistrationService service = new ShipmentRegistrationService(
                shipments, archive, tariffClient, new ChargeCalculator(), new ManifestMapper(),
                Clock.fixed(Instant.parse("2026-11-20T09:15:00Z"), ZoneOffset.UTC));

        DispatchManifest manifest = new DispatchManifest(
                "CONCURRENT-1",
                new CarrierDto("carrier-1", "Campus Couriers", "ops@campus-couriers.example"),
                LocalDate.parse("2026-11-20"),
                "EDI-01",
                List.of(new ParcelDto("TRF-1", new BigDecimal("2.5"))));
        byte[] raw = "{\"manifestNo\":\"CONCURRENT-1\"}".getBytes(StandardCharsets.UTF_8);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> attempt(service, manifest, raw, start));
            Future<String> second = pool.submit(() -> attempt(service, manifest, raw, start));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("registered", "duplicate");
        } finally {
            pool.shutdownNow();
        }

        assertThat(shipments.count()).isEqualTo(1);
        assertThat(archive.count()).isEqualTo(1);
    }

    private static String attempt(ShipmentRegistrationService service, DispatchManifest manifest,
                                  byte[] raw, CountDownLatch start) {
        try {
            start.await();
            service.register(manifest, raw);
            return "registered";
        } catch (DuplicateManifestException e) {
            return "duplicate";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private static final class FakeShipmentRepository implements ShipmentRepository {
        private final Map<String, ShipmentItem> items = new ConcurrentHashMap<>();

        @Override public void put(ShipmentItem item) { items.put(item.manifestNo(), item); }
        @Override public Optional<ShipmentItem> get(String shipmentRef) {
            return items.values().stream().filter(i -> i.shipmentRef().equals(shipmentRef)).findFirst();
        }
        @Override public boolean manifestExists(String manifestNo) { return items.containsKey(manifestNo); }
        @Override public List<ShipmentItem> listByCarrier(String carrierId) { return List.copyOf(items.values()); }
        @Override public void remove(String shipmentRef) { items.values().removeIf(i -> i.shipmentRef().equals(shipmentRef)); }
        int count() { return items.size(); }
    }

    private static final class FakeManifestArchive implements ManifestArchive {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override public void archive(String key, byte[] manifest) { objects.put(key, manifest.clone()); }
        @Override public Optional<byte[]> fetch(String key) { return Optional.ofNullable(objects.get(key)); }
        @Override public void remove(String key) { objects.remove(key); }
        int count() { return objects.size(); }
    }
}
