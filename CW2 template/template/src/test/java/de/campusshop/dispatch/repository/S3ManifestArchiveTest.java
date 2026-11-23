package de.campusshop.dispatch.repository;

import de.campusshop.dispatch.config.DispatchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repository test using a mocked {@link S3Client} (a fake backing store): no AWS or LocalStack is
 * needed, and the real S3 SDK request objects are inspected.
 */
@ExtendWith(MockitoExtension.class)
class S3ManifestArchiveTest {

    private static final String BUCKET = "test-manifest-bucket";
    private static final String KEY = "manifests/carrier-1/shipment-1.json";

    @Mock
    private S3Client s3;

    private S3ManifestArchive archive;

    @BeforeEach
    void setUp() {
        DispatchProperties properties = new DispatchProperties(
                new DispatchProperties.Archive(BUCKET),
                new DispatchProperties.Shipments("test-table"),
                new DispatchProperties.Aws("http://localhost:4566", "eu-central-1", "test", "test"));
        archive = new S3ManifestArchive(s3, properties);
    }

    @Test
    void archivesTheExactBytesWithJsonContentType() throws Exception {
        byte[] payload = "{\"carrierInternalNotes\":\"keep me\"}".getBytes(StandardCharsets.UTF_8);

        archive.archive(KEY, payload);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo(KEY);
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void fetchesStoredBytes() {
        byte[] payload = "{\"manifestNo\":\"m-1\"}".getBytes(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(payload);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        assertThat(archive.fetch(KEY)).contains(payload);

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo(KEY);
    }

    @Test
    void missingKeyBecomesEmpty() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThat(archive.fetch(KEY)).isEmpty();
    }

    @Test
    void generic404FromS3CompatibleEndpointBecomesEmpty() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchKey").build());

        assertThat(archive.fetch(KEY)).isEmpty();
    }

    @Test
    void non404S3FailureIsPropagated() {
        AwsServiceException failure = S3Exception.builder().statusCode(500).message("storage down").build();
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> archive.fetch(KEY)).isSameAs(failure);
    }

    @Test
    void deleteFailureIsPropagatedInsteadOfBeingHidden() {
        AwsServiceException failure = S3Exception.builder().statusCode(500).message("delete failed").build();
        doThrow(failure).when(s3).deleteObject(any(DeleteObjectRequest.class));

        assertThatThrownBy(() -> archive.remove(KEY)).isSameAs(failure);
    }

    @Test
    void removesTheObjectByKey() {
        archive.remove(KEY);

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo(KEY);
    }
}
