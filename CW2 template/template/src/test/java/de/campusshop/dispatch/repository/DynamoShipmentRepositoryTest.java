package de.campusshop.dispatch.repository;

import de.campusshop.dispatch.config.DispatchProperties;
import de.campusshop.dispatch.model.ShipmentItem;
import de.campusshop.dispatch.model.ShipmentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repository test using a mocked {@link DynamoDbClient}. Verifies the exact SDK operations, index
 * names, attribute types and pagination without needing LocalStack.
 */
@ExtendWith(MockitoExtension.class)
class DynamoShipmentRepositoryTest {

    private static final String TABLE = "test-shipments";
    private static final String REF_1 = "11111111-1111-4111-8111-111111111111";
    private static final String REF_2 = "22222222-2222-4222-8222-222222222222";

    @Mock
    private DynamoDbClient dynamo;

    private DynamoShipmentRepository repository;

    @BeforeEach
    void setUp() {
        DispatchProperties properties = new DispatchProperties(
                new DispatchProperties.Archive("test-bucket"),
                new DispatchProperties.Shipments(TABLE),
                new DispatchProperties.Aws("http://localhost:4566", "eu-central-1", "test", "test"));
        repository = new DynamoShipmentRepository(dynamo, properties);
    }

    @Test
    void putWritesAllAttributesWithTheCorrectTypes() {
        repository.put(item(REF_1, "2026-11-20", "16.99"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        PutItemRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo(TABLE);

        Map<String, AttributeValue> values = request.item();
        assertThat(values.get("shipmentRef").s()).isEqualTo(REF_1);
        assertThat(values.get("manifestNo").s()).isEqualTo("manifest-1");
        assertThat(values.get("carrierId").s()).isEqualTo("carrier-1");
        assertThat(values.get("dispatchDate").s()).isEqualTo("2026-11-20");
        assertThat(values.get("registeredAt").s()).isEqualTo("2026-11-20T09:15:00Z");
        assertThat(values.get("state").s()).isEqualTo("REGISTERED");
        assertThat(values.get("parcelCount").n()).isEqualTo("2");
        assertThat(values.get("totalCharge").n()).isEqualTo("16.99"); // toPlainString, never 1.699E+1
        assertThat(values.get("manifestKey").s()).isEqualTo("manifests/carrier-1/" + REF_1 + ".json");
    }

    @Test
    void getReadsTheItemFromTheBaseTable() {
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(attributes(REF_1, "2026-11-20", "16.99")).build());

        ShipmentItem result = repository.get(REF_1).orElseThrow();

        assertThat(result.shipmentRef()).isEqualTo(REF_1);
        assertThat(result.manifestNo()).isEqualTo("manifest-1");
        assertThat(result.carrierId()).isEqualTo("carrier-1");
        assertThat(result.dispatchDate()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(result.registeredAt()).isEqualTo(Instant.parse("2026-11-20T09:15:00Z"));
        assertThat(result.state()).isEqualTo(ShipmentState.REGISTERED);
        assertThat(result.parcelCount()).isEqualTo(2);
        assertThat(result.totalCharge()).isEqualByComparingTo("16.99");
        assertThat(result.manifestKey()).isEqualTo("manifests/carrier-1/" + REF_1 + ".json");

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo).getItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo(TABLE);
        assertThat(captor.getValue().key().get("shipmentRef").s()).isEqualTo(REF_1);
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void getReturnsEmptyForAnUnknownReference() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        assertThat(repository.get(REF_1)).isEmpty();
    }

    @Test
    void manifestExistsQueriesTheManifestIndex() {
        when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().count(1).build());

        assertThat(repository.manifestExists("manifest-1")).isTrue();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(captor.capture());
        QueryRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo(TABLE);
        assertThat(request.indexName()).isEqualTo("manifest-index");
        assertThat(request.keyConditionExpression()).isEqualTo("manifestNo = :manifestNo");
        assertThat(request.expressionAttributeValues().get(":manifestNo").s()).isEqualTo("manifest-1");
        assertThat(request.limit()).isEqualTo(1);
    }

    @Test
    void listByCarrierQueriesAscendingAndFollowsPagination() {
        QueryResponse firstPage = QueryResponse.builder()
                .items(attributes(REF_1, "2026-11-20", "8.25"))
                .lastEvaluatedKey(Map.of("shipmentRef", AttributeValue.fromS(REF_1)))
                .build();
        QueryResponse secondPage = QueryResponse.builder()
                .items(attributes(REF_2, "2026-11-21", "16.99"))
                .build();
        when(dynamo.query(any(QueryRequest.class))).thenReturn(firstPage, secondPage);

        List<ShipmentItem> result = repository.listByCarrier("carrier-1");

        assertThat(result).extracting(ShipmentItem::shipmentRef).containsExactly(REF_1, REF_2);
        assertThat(result).extracting(ShipmentItem::dispatchDate)
                .containsExactly(LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-21"));

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo, times(2)).query(captor.capture());
        List<QueryRequest> requests = captor.getAllValues();
        assertThat(requests.get(0).tableName()).isEqualTo(TABLE);
        assertThat(requests.get(0).indexName()).isEqualTo("carrier-dispatch-index");
        assertThat(requests.get(0).keyConditionExpression()).isEqualTo("carrierId = :carrierId");
        assertThat(requests.get(0).expressionAttributeValues().get(":carrierId").s()).isEqualTo("carrier-1");
        assertThat(requests.get(0).scanIndexForward()).isTrue();
        assertThat(requests.get(0).exclusiveStartKey()).isEmpty();
        assertThat(requests.get(1).exclusiveStartKey().get("shipmentRef").s()).isEqualTo(REF_1);
    }

    @Test
    void removeDeletesByPartitionKey() {
        repository.remove(REF_1);

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo).deleteItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo(TABLE);
        assertThat(captor.getValue().key().get("shipmentRef").s()).isEqualTo(REF_1);
    }

    private static ShipmentItem item(String ref, String dispatchDate, String totalCharge) {
        return new ShipmentItem(
                ref, "manifest-1", "carrier-1", LocalDate.parse(dispatchDate),
                Instant.parse("2026-11-20T09:15:00Z"), ShipmentState.REGISTERED, 2,
                new BigDecimal(totalCharge), "manifests/carrier-1/" + ref + ".json");
    }

    private static Map<String, AttributeValue> attributes(String ref, String dispatchDate, String totalCharge) {
        return Map.of(
                "shipmentRef", AttributeValue.fromS(ref),
                "manifestNo", AttributeValue.fromS("manifest-1"),
                "carrierId", AttributeValue.fromS("carrier-1"),
                "dispatchDate", AttributeValue.fromS(dispatchDate),
                "registeredAt", AttributeValue.fromS("2026-11-20T09:15:00Z"),
                "state", AttributeValue.fromS("REGISTERED"),
                "parcelCount", AttributeValue.fromN("2"),
                "totalCharge", AttributeValue.fromN(totalCharge),
                "manifestKey", AttributeValue.fromS("manifests/carrier-1/" + ref + ".json"));
    }
}
