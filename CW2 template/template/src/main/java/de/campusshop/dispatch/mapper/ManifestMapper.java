package de.campusshop.dispatch.mapper;

import de.campusshop.dispatch.dto.ParcelCharge;
import de.campusshop.dispatch.dto.ShipmentRecord;
import de.campusshop.dispatch.dto.ShipmentSummary;
import de.campusshop.dispatch.dto.TariffResponse;
import de.campusshop.dispatch.model.RatedParcel;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.model.Rating;
import de.campusshop.dispatch.model.Tariff;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts between the external tariff JSON, the internal model and the response DTOs.
 */
@Component
public class ManifestMapper {

    /** External tariff JSON to the internal {@link Tariff}. */
    public Tariff toTariff(TariffResponse response) {
        return new Tariff(
                response.tariffCode(),
                response.serviceName(),
                response.baseFee(),
                response.pricePerKg(),
                response.fuelSurchargeRate());
    }

    /** One priced parcel. For an UNRATED parcel all tariff fields and the charge are null. */
    public ParcelCharge toParcelCharge(RatedParcel parcel) {
        Tariff tariff = parcel.tariff();
        boolean rated = tariff != null;
        return new ParcelCharge(
                parcel.parcelNo(),
                parcel.tariffCode(),
                rated ? tariff.serviceName() : null,
                parcel.weightKg(),
                rated ? tariff.baseFee() : null,
                rated ? tariff.pricePerKg() : null,
                rated ? tariff.fuelSurchargeRate() : null,
                parcel.charge(),
                rated ? Rating.RATED : Rating.UNRATED);
    }

    /** The full registration response: the stored item plus every priced parcel. */
    public ShipmentRecord toRecord(ShipmentItem item, List<RatedParcel> parcels) {
        return new ShipmentRecord(
                item.shipmentRef(),
                item.manifestNo(),
                item.carrierId(),
                item.dispatchDate(),
                item.registeredAt(),
                item.state(),
                item.parcelCount(),
                item.totalCharge(),
                item.manifestKey(),
                parcels.stream().map(this::toParcelCharge).toList());
    }

    /** The stored item as returned by the read endpoints (no parcels). */
    public ShipmentSummary toSummary(ShipmentItem item) {
        return new ShipmentSummary(
                item.shipmentRef(),
                item.manifestNo(),
                item.carrierId(),
                item.dispatchDate(),
                item.registeredAt(),
                item.state(),
                item.parcelCount(),
                item.totalCharge(),
                item.manifestKey());
    }
}
