package de.campusshop.dispatch.service;

import de.campusshop.dispatch.client.TariffClient;
import de.campusshop.dispatch.dto.DispatchManifest;
import de.campusshop.dispatch.dto.ParcelDto;
import de.campusshop.dispatch.dto.ShipmentRecord;
import de.campusshop.dispatch.exception.DuplicateManifestException;
import de.campusshop.dispatch.mapper.ManifestMapper;
import de.campusshop.dispatch.model.RatedParcel;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.model.ShipmentState;
import de.campusshop.dispatch.model.Tariff;
import de.campusshop.dispatch.repository.ManifestArchive;
import de.campusshop.dispatch.repository.ManifestKeys;
import de.campusshop.dispatch.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Registers a validated manifest. Dependencies are wired for you through constructor injection.
 *
 * <p>The order of operations is: duplicate check, archive the raw body in S3, price every parcel,
 * index the shipment in DynamoDB. If the index write fails the archived object is removed again so
 * that no orphan manifest is left behind.
 */
@Service
public class ShipmentRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentRegistrationService.class);

    private final ShipmentRepository shipments;
    private final ManifestArchive archive;
    private final TariffClient tariffClient;
    private final ChargeCalculator calculator;
    private final ManifestMapper mapper;
    private final Clock clock;

    public ShipmentRegistrationService(ShipmentRepository shipments, ManifestArchive archive, TariffClient tariffClient,
                                       ChargeCalculator calculator, ManifestMapper mapper, Clock clock) {
        this.shipments = shipments;
        this.archive = archive;
        this.tariffClient = tariffClient;
        this.calculator = calculator;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * @param manifest    the parsed and validated manifest
     * @param rawManifest the request body exactly as received; this is what goes to S3
     */
    public ShipmentRecord register(DispatchManifest manifest, byte[] rawManifest) {
        if (shipments.manifestExists(manifest.manifestNo())) {
            log.warn("Duplicate manifest rejected manifestNo={}", manifest.manifestNo());
            throw new DuplicateManifestException(manifest.manifestNo());
        }

        String shipmentRef = UUID.randomUUID().toString();
        String manifestKey = ManifestKeys.keyFor(manifest.carrier().carrierId(), shipmentRef);
        archive.archive(manifestKey, rawManifest);

        List<RatedParcel> parcels = new ArrayList<>();
        int parcelNo = 1;
        for (ParcelDto parcel : manifest.parcels()) {
            Tariff tariff = null;
            BigDecimal charge = null;
            try {
                tariff = tariffClient.findTariff(parcel.tariffCode()).orElse(null);
                if (tariff != null) {
                    charge = calculator.charge(tariff, parcel.weightKg());
                }
            } catch (RuntimeException ex) {
                // A failed tariff lookup degrades this parcel to UNRATED; it does not fail the manifest.
                log.warn("Tariff lookup failed for tariffCode={}: {}", parcel.tariffCode(), ex.getMessage());
                tariff = null;
                charge = null;
            }
            if (tariff == null) {
                log.warn("Parcel is UNRATED parcelNo={} tariffCode={}", parcelNo, parcel.tariffCode());
            }
            parcels.add(new RatedParcel(parcelNo++, parcel.tariffCode(), parcel.weightKg(), tariff, charge));
        }

        BigDecimal totalCharge = parcels.stream()
                .map(RatedParcel::charge)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ShipmentItem item = new ShipmentItem(
                shipmentRef,
                manifest.manifestNo(),
                manifest.carrier().carrierId(),
                manifest.dispatchDate(),
                clock.instant(),
                ShipmentState.REGISTERED,
                parcels.size(),
                totalCharge,
                manifestKey);

        try {
            shipments.put(item);
        } catch (RuntimeException ex) {
            log.error("Indexing shipment {} failed — removing archived manifest {}", shipmentRef, manifestKey, ex);
            try {
                archive.remove(manifestKey);
            } catch (RuntimeException cleanupFailure) {
                log.error("Could not remove archived manifest {}", manifestKey, cleanupFailure);
            }
            throw ex;
        }

        log.info("Registered shipment {} manifestNo={} parcels={} totalCharge={}",
                shipmentRef, manifest.manifestNo(), parcels.size(), totalCharge);
        return mapper.toRecord(item, parcels);
    }
}
