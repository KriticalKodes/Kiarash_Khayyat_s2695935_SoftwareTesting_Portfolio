package de.campusshop.dispatch.repository;

import de.campusshop.dispatch.config.DispatchProperties;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.model.ShipmentState;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB implementation of {@link ShipmentRepository}. The table and both GSIs are pre-created by
 * the environment; never create them here. Use {@code GetItem} and {@code Query} only — no
 * {@code Scan}.
 *
 * <ul>
 *   <li>partition key {@code shipmentRef};</li>
 *   <li>{@code manifest-index}: partition key {@code manifestNo} (duplicate check);</li>
 *   <li>{@code carrier-dispatch-index}: partition key {@code carrierId}, sort key {@code dispatchDate}.</li>
 * </ul>
 */
@Repository
@Profile("!inmemory")
public class DynamoShipmentRepository implements ShipmentRepository {

    static final String MANIFEST_INDEX = "manifest-index";
    static final String CARRIER_DISPATCH_INDEX = "carrier-dispatch-index";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoShipmentRepository(DynamoDbClient dynamo, DispatchProperties properties) {
        this.dynamo = dynamo;
        this.table = properties.shipments().table();
    }

    @Override
    public void put(ShipmentItem item) {
        dynamo.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(toItem(item))
                .build());
    }

    @Override
    public Optional<ShipmentItem> get(String shipmentRef) {
        Map<String, AttributeValue> result = dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("shipmentRef", AttributeValue.fromS(shipmentRef)))
                .consistentRead(true)
                .build()).item();
        return (result == null || result.isEmpty())
                ? Optional.empty()
                : Optional.of(fromItem(result));
    }

    @Override
    public boolean manifestExists(String manifestNo) {
        // Uniqueness check via the GSI — Query, never Scan.
        return dynamo.query(QueryRequest.builder()
                .tableName(table)
                .indexName(MANIFEST_INDEX)
                .keyConditionExpression("manifestNo = :manifestNo")
                .expressionAttributeValues(Map.of(":manifestNo", AttributeValue.fromS(manifestNo)))
                .limit(1)
                .build()).count() > 0;
    }

    @Override
    public List<ShipmentItem> listByCarrier(String carrierId) {
        List<ShipmentItem> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(table)
                    .indexName(CARRIER_DISPATCH_INDEX)
                    .keyConditionExpression("carrierId = :carrierId")
                    .expressionAttributeValues(Map.of(":carrierId", AttributeValue.fromS(carrierId)))
                    .scanIndexForward(true); // oldest dispatchDate first
            if (startKey != null && !startKey.isEmpty()) {
                request.exclusiveStartKey(startKey);
            }
            QueryResponse response = dynamo.query(request.build());
            response.items().stream().map(DynamoShipmentRepository::fromItem).forEach(items::add);
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return items;
    }

    @Override
    public void remove(String shipmentRef) {
        dynamo.deleteItem(DeleteItemRequest.builder()
                .tableName(table)
                .key(Map.of("shipmentRef", AttributeValue.fromS(shipmentRef)))
                .build());
    }

    private static Map<String, AttributeValue> toItem(ShipmentItem item) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put("shipmentRef", AttributeValue.fromS(item.shipmentRef()));
        values.put("manifestNo", AttributeValue.fromS(item.manifestNo()));
        values.put("carrierId", AttributeValue.fromS(item.carrierId()));
        values.put("dispatchDate", AttributeValue.fromS(item.dispatchDate().toString()));
        values.put("registeredAt", AttributeValue.fromS(item.registeredAt().toString()));
        values.put("state", AttributeValue.fromS(item.state().name()));
        values.put("parcelCount", AttributeValue.fromN(String.valueOf(item.parcelCount())));
        values.put("totalCharge", AttributeValue.fromN(item.totalCharge().toPlainString()));
        values.put("manifestKey", AttributeValue.fromS(item.manifestKey()));
        return values;
    }

    private static ShipmentItem fromItem(Map<String, AttributeValue> values) {
        return new ShipmentItem(
                values.get("shipmentRef").s(),
                values.get("manifestNo").s(),
                values.get("carrierId").s(),
                LocalDate.parse(values.get("dispatchDate").s()),
                Instant.parse(values.get("registeredAt").s()),
                ShipmentState.valueOf(values.get("state").s()),
                Integer.parseInt(values.get("parcelCount").n()),
                new BigDecimal(values.get("totalCharge").n()),
                values.get("manifestKey").s());
    }
}
