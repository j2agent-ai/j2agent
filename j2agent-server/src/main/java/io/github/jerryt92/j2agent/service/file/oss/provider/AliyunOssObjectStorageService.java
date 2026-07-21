package io.github.jerryt92.j2agent.service.file.oss.provider;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.exception.ObjectStorageException;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 OSS 对象存储实现。
 */
public class AliyunOssObjectStorageService implements ObjectStorageService {
    private final OSS client;
    private final String defaultBucket;

    public AliyunOssObjectStorageService(OSS client, String defaultBucket) {
        this.client = client;
        this.defaultBucket = ObjectStorageSupport.requireName(defaultBucket, "defaultBucket");
    }

    @Override
    public String getProvider() {
        return "Alibaba OSS";
    }

    @Override
    public String getDefaultBucket() {
        return defaultBucket;
    }

    @Override
    public void putObject(String bucket, String objectName, InputStream inputStream, long size, String contentType) {
        validateUpload(bucket, objectName, inputStream, size);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(size);
            if (contentType != null && !contentType.isBlank()) {
                metadata.setContentType(contentType);
            }
            client.putObject(new PutObjectRequest(bucket, objectName, inputStream, metadata));
        } catch (Exception e) {
            throw failure("upload", bucket, objectName, e);
        }
    }

    @Override
    public InputStream getObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        try {
            OSSObject object = client.getObject(bucket, objectName);
            return object.getObjectContent();
        } catch (Exception e) {
            throw failure("download", bucket, objectName, e);
        }
    }

    @Override
    public void removeObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        try {
            client.deleteObject(bucket, objectName);
        } catch (Exception e) {
            throw failure("delete", bucket, objectName, e);
        }
    }

    @Override
    public boolean objectExists(String bucket, String objectName) {
        validateObject(bucket, objectName);
        try {
            return client.doesObjectExist(bucket, objectName);
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode()) || "NoSuchBucket".equals(e.getErrorCode())) {
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
        try {
            ObjectMetadata metadata = client.getObjectMetadata(bucket, objectName);
            return new ObjectStorageObject(
                    objectName,
                    metadata.getETag(),
                    metadata.getContentLength(),
                    metadata.getContentType(),
                    metadata.getLastModified().getTime()
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
        try {
            ListObjectsV2Request request = new ListObjectsV2Request(bucket)
                    .withPrefix(prefix == null ? "" : prefix)
                    .withMaxKeys(ObjectStorageSupport.pageSize(pageSize));
            if (continuationToken != null && !continuationToken.isBlank()) {
                request.setContinuationToken(continuationToken);
            }
            ListObjectsV2Result result = client.listObjectsV2(request);
            List<ObjectStorageObject> objects = result.getObjectSummaries().stream()
                    .map(this::toObject)
                    .toList();
            return new ObjectStoragePage(
                    objects,
                    result.isTruncated() ? result.getNextContinuationToken() : null
            );
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to list objects in " + bucket + " with Alibaba OSS", e);
        }
    }

    @Override
    public URL generatePresignedUrl(String bucket, String objectName, Duration expiry) {
        validateObject(bucket, objectName);
        try {
            long expiryMillis = Math.multiplyExact(ObjectStorageSupport.expirySeconds(expiry), 1000L);
            Date expiration = new Date(Math.addExact(System.currentTimeMillis(), expiryMillis));
            return client.generatePresignedUrl(bucket, objectName, expiration, HttpMethod.GET);
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
        try {
            int expirySeconds = ObjectStorageSupport.expirySeconds(expiry);
            long expiryMillis = Math.multiplyExact(expirySeconds, 1000L);
            Date expiration = new Date(Math.addExact(System.currentTimeMillis(), expiryMillis));
            URL url = client.generatePresignedUrl(bucket, objectName, expiration, HttpMethod.PUT);
            Map<String, String> headers = new HashMap<>();
            if (contentType != null && !contentType.isBlank()) {
                headers.put("Content-Type", contentType);
            }
            return new PresignedUploadCredential(
                    getProvider(),
                    url.toString(),
                    "PUT",
                    Map.copyOf(headers),
                    System.currentTimeMillis() + expiryMillis,
                    Map.of()
            );
        } catch (Exception e) {
            throw failure("generate upload URL for", bucket, objectName, e);
        }
    }

    @Override
    public void close() {
        client.shutdown();
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

    private void validateContentLength(long contentLength) {
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
    }

    private void validateObject(String bucket, String objectName) {
        ObjectStorageSupport.requireName(bucket, "bucket");
        ObjectStorageSupport.requireName(objectName, "objectName");
    }

    private ObjectStorageException failure(String operation, String bucket, String objectName, Exception cause) {
        return new ObjectStorageException(
                "Failed to " + operation + " object " + bucket + "/" + objectName + " with Alibaba OSS",
                cause
        );
    }

    private ObjectStorageObject toObject(OSSObjectSummary summary) {
        return new ObjectStorageObject(
                summary.getKey(),
                summary.getETag(),
                summary.getSize(),
                null,
                summary.getLastModified().getTime()
        );
    }
}
