package io.github.jerryt92.j2agent.service.file.oss.provider;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.exception.ObjectStorageException;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S3 兼容对象存储实现。
 */
public class S3CompatibleObjectStorageService implements ObjectStorageService {
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String defaultBucket;
    private final String providerName;
    private final boolean autoCreateBucket;
    private volatile boolean defaultBucketReady;

    protected S3CompatibleObjectStorageService(
            S3Client s3Client,
            S3Presigner presigner,
            String defaultBucket,
            String providerName,
            boolean autoCreateBucket
    ) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.defaultBucket = ObjectStorageSupport.requireName(defaultBucket, "defaultBucket");
        this.providerName = ObjectStorageSupport.requireName(providerName, "providerName");
        this.autoCreateBucket = autoCreateBucket;
    }

    @Override
    public String getProvider() {
        return providerName;
    }

    @Override
    public String getDefaultBucket() {
        return defaultBucket;
    }

    @Override
    public void putObject(String bucket, String objectName, InputStream inputStream, long size, String contentType) {
        validateUpload(bucket, objectName, inputStream, size);
        ensureBucket(bucket);
        try {
            PutObjectRequest.Builder builder = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            s3Client.putObject(builder.build(), RequestBody.fromInputStream(inputStream, size));
        } catch (Exception e) {
            throw failure("upload", bucket, objectName, e);
        }
    }

    @Override
    public InputStream getObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName)
                    .build());
        } catch (Exception e) {
            throw failure("download", bucket, objectName, e);
        }
    }

    @Override
    public void removeObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName)
                    .build());
            removeEmptyParentDirectoryMarkers(bucket, objectName);
        } catch (Exception e) {
            throw failure("delete", bucket, objectName, e);
        }
    }

    @Override
    public boolean objectExists(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName)
                    .build());
            return true;
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw failure("check existence of", bucket, objectName, e);
        } catch (Exception e) {
            throw failure("check existence of", bucket, objectName, e);
        }
    }

    @Override
    public ObjectStorageObject getObjectMetadata(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName)
                    .build());
            return new ObjectStorageObject(
                    objectName,
                    head.eTag(),
                    head.contentLength(),
                    head.contentType(),
                    head.lastModified() == null ? 0L : head.lastModified().toEpochMilli()
            );
        } catch (Exception e) {
            throw failure("read metadata of", bucket, objectName, e);
        }
    }

    @Override
    public ObjectStoragePage listObjects(
            String bucket,
            String prefix,
            String continuationToken,
            int pageSize
    ) {
        ObjectStorageSupport.requireName(bucket, "bucket");
        ensureBucket(bucket);
        int normalizedPageSize = ObjectStorageSupport.pageSize(pageSize);
        try {
            ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix == null ? "" : prefix)
                    .maxKeys(normalizedPageSize);
            if (continuationToken != null && !continuationToken.isBlank()) {
                builder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3Client.listObjectsV2(builder.build());
            List<ObjectStorageObject> objects = new ArrayList<>(normalizedPageSize);
            for (S3Object item : response.contents()) {
                if (ObjectKeyUtils.isDirectoryMarker(item.key())) {
                    continue;
                }
                objects.add(new ObjectStorageObject(
                        item.key(),
                        item.eTag(),
                        item.size(),
                        null,
                        item.lastModified() == null ? 0L : item.lastModified().toEpochMilli()
                ));
            }
            String nextToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
            return new ObjectStoragePage(List.copyOf(objects), nextToken);
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to list objects in " + bucket + " with " + providerName, e);
        }
    }

    @Override
    public URL generatePresignedUrl(String bucket, String objectName, Duration expiry) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            int expirySeconds = ObjectStorageSupport.expirySeconds(expiry);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expirySeconds))
                    .getObjectRequest(getObjectRequest)
                    .build();
            return presigner.presignGetObject(presignRequest).url();
        } catch (Exception e) {
            throw failure("generate URL for", bucket, objectName, e);
        }
    }

    @Override
    public PresignedUploadCredential generatePresignedUploadUrl(
            String bucket,
            String objectName,
            Duration expiry,
            String contentType,
            long contentLength
    ) {
        validateObject(bucket, objectName);
        validateContentLength(contentLength);
        ensureBucket(bucket);
        try {
            int expirySeconds = ObjectStorageSupport.expirySeconds(expiry);
            PutObjectRequest.Builder putObjectBuilder = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName);
            Map<String, String> headers = new HashMap<>();
            if (contentType != null && !contentType.isBlank()) {
                putObjectBuilder.contentType(contentType);
                headers.put("Content-Type", contentType);
            }
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expirySeconds))
                    .putObjectRequest(putObjectBuilder.build())
                    .build();
            String url = presigner.presignPutObject(presignRequest).url().toString();
            return new PresignedUploadCredential(
                    providerName,
                    url,
                    "PUT",
                    Map.copyOf(headers),
                    System.currentTimeMillis() + expirySeconds * 1000L,
                    Map.of()
            );
        } catch (Exception e) {
            throw failure("generate upload URL for", bucket, objectName, e);
        }
    }

    @Override
    public void close() {
        s3Client.close();
        presigner.close();
    }

    private void validateContentLength(long contentLength) {
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
    }

    private void validateUpload(String bucket, String objectName, InputStream inputStream, long size) {
        validateObject(bucket, objectName);
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }

    private void validateObject(String bucket, String objectName) {
        ObjectStorageSupport.requireName(bucket, "bucket");
        ObjectStorageSupport.requireName(objectName, "objectName");
    }

    private void ensureBucket(String bucket) {
        if (!autoCreateBucket || !defaultBucket.equals(bucket) || defaultBucketReady) {
            return;
        }
        synchronized (this) {
            if (defaultBucketReady) {
                return;
            }
            try {
                try {
                    s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                } catch (NoSuchBucketException e) {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                }
                defaultBucketReady = true;
            } catch (S3Exception e) {
                if (e.statusCode() == 403) {
                    throw new ObjectStorageException(
                            "Failed to initialize bucket " + bucket + " with " + providerName
                                    + ": access denied, check j2agent.storage.s3 access-key-id and secret-access-key",
                            e
                    );
                }
                throw new ObjectStorageException(
                        "Failed to initialize bucket " + bucket + " with " + providerName,
                        e
                );
            } catch (Exception e) {
                throw new ObjectStorageException(
                        "Failed to initialize bucket " + bucket + " with " + providerName,
                        e
                );
            }
        }
    }

    private void removeEmptyParentDirectoryMarkers(String bucket, String deletedObjectName) {
        for (String prefix : ObjectKeyUtils.parentDirectoryPrefixes(deletedObjectName)) {
            if (prefixHasRemainingObjects(bucket, prefix)) {
                break;
            }
            removeDirectoryMarkerIfPresent(bucket, prefix);
        }
    }

    private boolean prefixHasRemainingObjects(String bucket, String prefix) {
        try {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build());
            for (S3Object item : response.contents()) {
                if (!prefix.equals(item.key())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw failure("inspect directory", bucket, prefix, e);
        }
    }

    private void removeDirectoryMarkerIfPresent(String bucket, String prefix) {
        if (!objectExists(bucket, prefix)) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(prefix)
                    .build());
        } catch (Exception e) {
            throw failure("delete directory marker", bucket, prefix, e);
        }
    }

    private ObjectStorageException failure(String operation, String bucket, String objectName, Exception cause) {
        return new ObjectStorageException(
                "Failed to " + operation + " object " + bucket + "/" + objectName + " with " + providerName,
                cause
        );
    }
}
