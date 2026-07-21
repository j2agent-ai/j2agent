package io.github.jerryt92.j2agent.config.storage;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;

import io.github.jerryt92.j2agent.service.file.oss.provider.AliyunOssObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.QiniuObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.S3ObjectStorageService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStorageConfigTest {
    private final ObjectStorageConfig config = new ObjectStorageConfig();

    @Test
    void shouldCreateS3Service() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.S3);
        properties.getS3().setEndpoint("http://127.0.0.1:9000");
        properties.getS3().setAccessKeyId("access-key-id");
        properties.getS3().setSecretAccessKey("secret-access-key");

        assertInstanceOf(S3ObjectStorageService.class, config.objectStorageService(properties));
    }

    @Test
    void shouldCreateAliyunOssService() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.ALIYUN_OSS);
        properties.getAliyunOss().setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        properties.getAliyunOss().setAccessKeyId("access-key");
        properties.getAliyunOss().setAccessKeySecret("secret-key");

        ObjectStorageService service = config.objectStorageService(properties);
        assertInstanceOf(AliyunOssObjectStorageService.class, service);
        service.close();
    }

    @Test
    void shouldCreateQiniuService() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.QINIU);
        properties.getQiniu().setAccessKey("access-key");
        properties.getQiniu().setSecretKey("secret-key");
        properties.getQiniu().setDomain("cdn.example.com");

        assertInstanceOf(QiniuObjectStorageService.class, config.objectStorageService(properties));
    }

    @Test
    void shouldRejectMissingSecretAccessKeyForS3Storage() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.S3);
        properties.getS3().setEndpoint("http://127.0.0.1:9000");
        properties.getS3().setAccessKeyId("access-key-id");

        assertThrows(IllegalArgumentException.class, () -> config.objectStorageService(properties));
    }

    @Test
    void shouldRejectIncompleteProviderConfiguration() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.S3);

        assertThrows(IllegalArgumentException.class, () -> config.objectStorageService(properties));
    }

    private ObjectStorageProperties baseProperties(ObjectStorageProperties.StorageType type) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType(type);
        properties.setBucket("test-bucket");
        return properties;
    }
}
