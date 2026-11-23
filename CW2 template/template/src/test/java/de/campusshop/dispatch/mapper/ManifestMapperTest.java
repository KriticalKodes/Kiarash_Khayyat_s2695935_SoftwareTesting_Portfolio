package de.campusshop.dispatch.mapper;

import de.campusshop.dispatch.dto.ParcelCharge;
import de.campusshop.dispatch.dto.ShipmentRecord;
import de.campusshop.dispatch.dto.ShipmentSummary;
import de.campusshop.dispatch.dto.TariffResponse;
import de.campusshop.dispatch.model.RatedParcel;
import de.campusshop.dispatch.model.Rating;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.model.ShipmentState;
import de.campusshop.dispatch.model.Tariff;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestMapperTest {

    private final ManifestMapper mapper = new ManifestMapper();

    private static final Instant REGISTERED_AT = Instant.parse("2026-11-20T09:15:00Z");
    private static final Tariff TARIFF = new Tariff(
            "TRF-1", "Standard", new BigDecimal("4.50"), new BigDecimal("1.20"), new BigDecimal("0.10"));

    @Test
    void mapsExternalTariffJsonToTheInternalModel() {
        TariffResponse response = new TariffResponse(
                "TRF-1", "Standard", new BigDecimal("4.50"), new BigDecimal("1.20"), new BigDecimal("0.10"), 2);

        Tariff tariff = mapper.toTariff(response);

        assertThat(tariff.code()).isEqualTo("TRF-1");
        assertThat(tariff.serviceName()).isEqualTo("Standard");
        assertThat(tariff.baseFee()).isEqualByComparingTo("4.50");
        assertThat(tariff.pricePerKg()).isEqualByComparingTo("1.20");
        assertThat(tariff.fuelSurchargeRate()).isEqualByComparingTo("0.10");
    }

    @Test
    void mapsAPricedParcelWithAllTariffFields() {
        ParcelCharge charge = mapper.toParcelCharge(
                new RatedParcel(1, "TRF-1", new BigDecimal("2.5"), TARIFF, new BigDecimal("8.25")));

        assertThat(charge.parcelNo()).isEqualTo(1);
        assertThat(charge.tariffCode()).isEqualTo("TRF-1");
        assertThat(charge.serviceName()).isEqualTo("Standard");
        assertThat(charge.weightKg()).isEqualByComparingTo("2.5");
        assertThat(charge.baseFee()).isEqualByComparingTo("4.50");
        assertThat(charge.pricePerKg()).isEqualByComparingTo("1.20");
        assertThat(charge.fuelSurchargeRate()).isEqualByComparingTo("0.10");
        assertThat(charge.charge()).isEqualByComparingTo("8.25");
        assertThat(charge.rating()).isEqualTo(Rating.RATED);
    }

    @Test
    void mapsAnUnratedParcelWithNullTariffFields() {
        ParcelCharge charge = mapper.toParcelCharge(
                new RatedParcel(2, "TRF-999", BigDecimal.ONE, null, null));

        assertThat(charge.parcelNo()).isEqualTo(2);
        assertThat(charge.tariffCode()).isEqualTo("TRF-999");
        assertThat(charge.weightKg()).isEqualByComparingTo("1");
        assertThat(charge.serviceName()).isNull();
        assertThat(charge.baseFee()).isNull();
        assertThat(charge.pricePerKg()).isNull();
        assertThat(charge.fuelSurchargeRate()).isNull();
        assertThat(charge.charge()).isNull();
        assertThat(charge.rating()).isEqualTo(Rating.UNRATED);
    }

    @Test
    void mapsItemAndParcelsToTheFullRecord() {
        ShipmentRecord record = mapper.toRecord(item(), List.of(
                new RatedParcel(1, "TRF-1", new BigDecimal("2.5"), TARIFF, new BigDecimal("8.25")),
                new RatedParcel(2, "TRF-999", BigDecimal.ONE, null, null)));

        assertThat(record.shipmentRef()).isEqualTo("shipment-1");
        assertThat(record.manifestNo()).isEqualTo("manifest-1");
        assertThat(record.carrierId()).isEqualTo("carrier-1");
        assertThat(record.dispatchDate()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(record.registeredAt()).isEqualTo(REGISTERED_AT);
        assertThat(record.state()).isEqualTo(ShipmentState.REGISTERED);
        assertThat(record.parcelCount()).isEqualTo(2);
        assertThat(record.totalCharge()).isEqualByComparingTo("8.25");
        assertThat(record.manifestKey()).isEqualTo("manifests/carrier-1/shipment-1.json");
        assertThat(record.parcels()).hasSize(2);
        assertThat(record.parcels().get(0).rating()).isEqualTo(Rating.RATED);
        assertThat(record.parcels().get(1).rating()).isEqualTo(Rating.UNRATED);
    }

    @Test
    void mapsItemToSummaryWithoutParcels() {
        ShipmentSummary summary = mapper.toSummary(item());

        assertThat(summary.shipmentRef()).isEqualTo("shipment-1");
        assertThat(summary.manifestNo()).isEqualTo("manifest-1");
        assertThat(summary.carrierId()).isEqualTo("carrier-1");
        assertThat(summary.dispatchDate()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(summary.registeredAt()).isEqualTo(REGISTERED_AT);
        assertThat(summary.state()).isEqualTo(ShipmentState.REGISTERED);
        assertThat(summary.parcelCount()).isEqualTo(2);
        assertThat(summary.totalCharge()).isEqualByComparingTo("8.25");
        assertThat(summary.manifestKey()).isEqualTo("manifests/carrier-1/shipment-1.json");
    }

    private static ShipmentItem item() {
        return new ShipmentItem(
                "shipment-1", "manifest-1", "carrier-1", LocalDate.parse("2026-11-20"),
                REGISTERED_AT, ShipmentState.REGISTERED, 2, new BigDecimal("8.25"),
                "manifests/carrier-1/shipment-1.json");
    }
}
