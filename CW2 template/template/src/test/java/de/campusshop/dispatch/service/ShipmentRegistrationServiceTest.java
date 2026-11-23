package de.campusshop.dispatch.service;

import de.campusshop.dispatch.client.TariffClient;
import de.campusshop.dispatch.dto.CarrierDto;
import de.campusshop.dispatch.dto.DispatchManifest;
import de.campusshop.dispatch.dto.ParcelCharge;
import de.campusshop.dispatch.dto.ParcelDto;
import de.campusshop.dispatch.dto.ShipmentRecord;
import de.campusshop.dispatch.exception.DuplicateManifestException;
import de.campusshop.dispatch.mapper.ManifestMapper;
import de.campusshop.dispatch.model.Rating;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.model.ShipmentState;
import de.campusshop.dispatch.model.Tariff;
import de.campusshop.dispatch.repository.ManifestArchive;
import de.campusshop.dispatch.repository.ManifestKeys;
import de.campusshop.dispatch.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShipmentRegistrationService} with <b>mocked</b> repository, archive and
 * tariff client. Cover the success path, the UNRATED path, the duplicate path and the clean-up when
 * the DynamoDB write fails. The mocks are wired for you below.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentRegistrationServiceTest {

    private static final String MANIFEST_NO = "4b1d2c3e-5f60-4a71-8b92-a3b4c5d6e7f8";
    private static final String CARRIER_ID = "9c8b7a65-4d3e-4f21-9a0b-1c2d3e4f5a6b";
    private static final byte[] RAW_MANIFEST = "{\"manifestNo\":\"raw-body\"}".getBytes(StandardCharsets.UTF_8);

    private static final Tariff STANDARD = new Tariff(
            "TRF-1", "Standard", new BigDecimal("4.50"), new BigDecimal("1.20"), new BigDecimal("0.10"));
    private static final Tariff ECONOMY = new Tariff(
            "TRF-2", "Economy", new BigDecimal("3.00"), new BigDecimal("0.80"), new BigDecimal("0.05"));

    @Mock
    private ShipmentRepository shipments;
    @Mock
    private ManifestArchive archive;
    @Mock
    private TariffClient tariffClient;

    private ShipmentRegistrationService service;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T09:15:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new ShipmentRegistrationService(shipments, archive, tariffClient, new ChargeCalculator(),
                new ManifestMapper(), clock);
    }

    @Test
    void registersArchivesAndIndexesAShipment() {
        when(shipments.manifestExists(MANIFEST_NO)).thenReturn(false);
        when(tariffClient.findTariff("TRF-1")).thenReturn(Optional.of(STANDARD));
        when(tariffClient.findTariff("TRF-2")).thenReturn(Optional.of(ECONOMY));

        ShipmentRecord record = service.register(
                manifest(MANIFEST_NO,
                        new ParcelDto("TRF-1", new BigDecimal("2.5")),
                        new ParcelDto("TRF-2", new BigDecimal("0.8"))),
                RAW_MANIFEST);

        assertThat(record.shipmentRef()).isNotBlank();
        assertThat(record.manifestNo()).isEqualTo(MANIFEST_NO);
        assertThat(record.carrierId()).isEqualTo(CARRIER_ID);
        assertThat(record.dispatchDate()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(record.registeredAt()).isEqualTo(clock.instant());
        assertThat(record.state()).isEqualTo(ShipmentState.REGISTERED);
        assertThat(record.parcelCount()).isEqualTo(2);
        assertThat(record.parcels()).hasSize(2);
        assertThat(record.totalCharge()).isEqualByComparingTo("12.07");

        ParcelCharge first = record.parcels().get(0);
        assertThat(first.parcelNo()).isEqualTo(1);
        assertThat(first.tariffCode()).isEqualTo("TRF-1");
        assertThat(first.serviceName()).isEqualTo("Standard");
        assertThat(first.baseFee()).isEqualByComparingTo("4.50");
        assertThat(first.charge()).isEqualByComparingTo("8.25"); // (4.50 + 1.20*2.5) * 1.10
        assertThat(first.rating()).isEqualTo(Rating.RATED);

        ParcelCharge second = record.parcels().get(1);
        assertThat(second.parcelNo()).isEqualTo(2);
        assertThat(second.charge()).isEqualByComparingTo("3.82"); // (3.00 + 0.80*0.8) * 1.05 = 3.822
        assertThat(second.rating()).isEqualTo(Rating.RATED);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(archive).archive(keyCaptor.capture(), bodyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(ManifestKeys.keyFor(CARRIER_ID, record.shipmentRef()));
        assertThat(bodyCaptor.getValue()).isEqualTo(RAW_MANIFEST);

        ArgumentCaptor<ShipmentItem> itemCaptor = ArgumentCaptor.forClass(ShipmentItem.class);
        verify(shipments).put(itemCaptor.capture());
        assertThat(itemCaptor.getValue()).isEqualTo(new ShipmentItem(
                record.shipmentRef(), MANIFEST_NO, CARRIER_ID, LocalDate.parse("2026-11-20"),
                clock.instant(), ShipmentState.REGISTERED, 2, new BigDecimal("12.07"), keyCaptor.getValue()));
        assertThat(itemCaptor.getValue().manifestKey()).isEqualTo(record.manifestKey());
    }

    @Test
    void unknownTariffLeavesTheParcelUnrated() {
        when(shipments.manifestExists(MANIFEST_NO)).thenReturn(false);
        when(tariffClient.findTariff("TRF-1")).thenReturn(Optional.of(STANDARD));
        when(tariffClient.findTariff("TRF-999")).thenReturn(Optional.empty());

        ShipmentRecord record = service.register(
                manifest(MANIFEST_NO,
                        new ParcelDto("TRF-1", new BigDecimal("2.5")),
                        new ParcelDto("TRF-999", BigDecimal.ONE)),
                RAW_MANIFEST);

        assertThat(record.parcelCount()).isEqualTo(2);
        assertThat(record.parcels().get(0).rating()).isEqualTo(Rating.RATED);
        assertThat(record.parcels().get(0).charge()).isEqualByComparingTo("8.25");

        ParcelCharge unrated = record.parcels().get(1);
        assertThat(unrated.parcelNo()).isEqualTo(2);
        assertThat(unrated.tariffCode()).isEqualTo("TRF-999");
        assertThat(unrated.rating()).isEqualTo(Rating.UNRATED);
        assertThat(unrated.serviceName()).isNull();
        assertThat(unrated.baseFee()).isNull();
        assertThat(unrated.pricePerKg()).isNull();
        assertThat(unrated.fuelSurchargeRate()).isNull();
        assertThat(unrated.charge()).isNull();

        // Unrated parcels are counted but excluded from the total.
        assertThat(record.totalCharge()).isEqualByComparingTo("8.25");
        verify(shipments).put(any());
    }

    @Test
    void duplicateManifestIsRejectedBeforeAnythingIsWritten() {
        when(shipments.manifestExists(MANIFEST_NO)).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                manifest(MANIFEST_NO, new ParcelDto("TRF-1", BigDecimal.ONE)), RAW_MANIFEST))
                .isInstanceOf(DuplicateManifestException.class)
                .hasMessageContaining(MANIFEST_NO);

        verify(archive, never()).archive(anyString(), any(byte[].class));
        verify(shipments, never()).put(any());
        verifyNoInteractions(tariffClient);
    }

    @Test
    void failedIndexWriteRemovesTheArchivedManifest() {
        when(shipments.manifestExists(MANIFEST_NO)).thenReturn(false);
        when(tariffClient.findTariff(anyString())).thenReturn(Optional.empty());
        IllegalStateException failure = new IllegalStateException("DynamoDB unavailable");
        doThrow(failure).when(shipments).put(any());

        assertThatThrownBy(() -> service.register(
                manifest(MANIFEST_NO, new ParcelDto("TRF-1", BigDecimal.ONE)), RAW_MANIFEST))
                .isSameAs(failure);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(archive).archive(keyCaptor.capture(), any(byte[].class));
        verify(archive).remove(keyCaptor.getValue());
    }

    @Test
    void compensationFailureDoesNotHideTheOriginalIndexFailure() {
        when(shipments.manifestExists(MANIFEST_NO)).thenReturn(false);
        when(tariffClient.findTariff(anyString())).thenReturn(Optional.empty());
        IllegalStateException failure = new IllegalStateException("DynamoDB unavailable");
        doThrow(failure).when(shipments).put(any());
        doThrow(new IllegalStateException("S3 cleanup failed")).when(archive).remove(anyString());

        assertThatThrownBy(() -> service.register(
                manifest(MANIFEST_NO, new ParcelDto("TRF-1", BigDecimal.ONE)), RAW_MANIFEST))
                .isSameAs(failure);

        verify(archive).remove(anyString());
    }

    private static DispatchManifest manifest(String manifestNo, ParcelDto... parcels) {
        return new DispatchManifest(
                manifestNo,
                new CarrierDto(CARRIER_ID, "Campus Couriers", "ops@campus-couriers.example"),
                LocalDate.parse("2026-11-20"),
                "EDI-01",
                List.of(parcels));
    }
}
