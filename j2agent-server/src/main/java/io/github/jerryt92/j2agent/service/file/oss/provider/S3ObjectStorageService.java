package io.github.jerryt92.j2agent.service.file.oss.provider;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 兼容对象存储实现。
 */
public class S3ObjectStorageService extends S3CompatibleObjectStorageService {
    public S3ObjectStorageService(S3Client s3Client, S3Presigner presigner, String defaultBucket) {
        super(s3Client, presigner, defaultBucket, "S3", true);
    }
}
