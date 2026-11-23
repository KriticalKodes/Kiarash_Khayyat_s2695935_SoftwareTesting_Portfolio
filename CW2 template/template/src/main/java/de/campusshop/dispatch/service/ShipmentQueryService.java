package de.campusshop.dispatch.service;

import de.campusshop.dispatch.dto.ShipmentSummary;
import de.campusshop.dispatch.exception.ShipmentNotFoundException;
import de.campusshop.dispatch.mapper.ManifestMapper;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.repository.ManifestArchive;
import de.campusshop.dispatch.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read and delete operations on registered shipments. Unknown shipments end in
 * {@code ShipmentNotFoundException} (404).
 *
 * <p>Deletion policy: a delete is a hard delete. The DynamoDB shipment item is removed first and
 * its archived raw manifest is removed from S3 with it, so no orphan manifest is left behind and
 * the manifestNo can be registered again. The endpoint therefore returns 204 No Content.
 */
@Service
public class ShipmentQueryService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentQueryService.class);

    private final ShipmentRepository shipments;
    private final ManifestArchive archive;
    private final ManifestMapper mapper;

    public ShipmentQueryService(ShipmentRepository shipments, ManifestArchive archive, ManifestMapper mapper) {
        this.shipments = shipments;
        this.archive = archive;
        this.mapper = mapper;
    }

    public ShipmentSummary find(String shipmentRef) {
        return shipments.get(shipmentRef)
                .map(mapper::toSummary)
                .orElseThrow(() -> new ShipmentNotFoundException(shipmentRef));
    }

    /** Oldest dispatchDate first. */
    public List<ShipmentSummary> listByCarrier(String carrierId) {
        return shipments.listByCarrier(carrierId).stream()
                .map(mapper::toSummary)
                .toList();
    }

    /** The archived manifest bytes. */
    public byte[] manifest(String shipmentRef) {
        ShipmentItem item = shipments.get(shipmentRef)
                .orElseThrow(() -> new ShipmentNotFoundException(shipmentRef));
        return archive.fetch(item.manifestKey())
                .orElseThrow(() -> {
                    log.warn("Archived manifest missing shipmentRef={} key={}", shipmentRef, item.manifestKey());
                    return new ShipmentNotFoundException(shipmentRef);
                });
    }

    public void delete(String shipmentRef) {
        ShipmentItem item = shipments.get(shipmentRef)
                .orElseThrow(() -> new ShipmentNotFoundException(shipmentRef));
        shipments.remove(shipmentRef);
        archive.remove(item.manifestKey());
        log.info("Deleted shipment {} and its archived manifest {}", shipmentRef, item.manifestKey());
    }
}
