package io.github.jerryt92.j2agent.config.storage;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.AliyunOssObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.QiniuObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.S3ObjectStorageService;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * 根据配置装配对象存储实现，业务侧只依赖 {@link ObjectStorageService}。
 */
@Configuration
@ConditionalOnProperty(prefix = "j2agent.storage", name = "enabled", havingValue = "true")
public class ObjectStorageConfig {

    @Bean(destroyMethod = "close")
    public ObjectStorageService objectStorageService(ObjectStorageProperties properties) {
        requireText(properties.getBucket(), "j2agent.storage.bucket");
        return switch (properties.getType()) {
            case S3 -> createS3Service(properties);
            case ALIYUN_OSS -> createAliyunOssService(properties);
            case QINIU -> createQiniuService(properties);
        };
    }

    private ObjectStorageService createS3Service(ObjectStorageProperties properties) {
        S3Clients clients = createS3Clients(properties.getS3(), "j2agent.storage.s3");
        return new S3ObjectStorageService(clients.s3Client(), clients.presigner(), properties.getBucket());
    }

    private ObjectStorageService createAliyunOssService(ObjectStorageProperties properties) {
        ObjectStorageProperties.AliyunOss aliyunOss = properties.getAliyunOss();
        requireText(aliyunOss.getEndpoint(), "j2agent.storage.aliyun-oss.endpoint");
        requireText(aliyunOss.getAccessKeyId(), "j2agent.storage.aliyun-oss.access-key-id");
        requireText(aliyunOss.getAccessKeySecret(), "j2agent.storage.aliyun-oss.access-key-secret");
        OSS client = new OSSClientBuilder().build(
                aliyunOss.getEndpoint(),
                aliyunOss.getAccessKeyId(),
                aliyunOss.getAccessKeySecret()
        );
        return new AliyunOssObjectStorageService(client, properties.getBucket());
    }

    private ObjectStorageService createQiniuService(ObjectStorageProperties properties) {
        ObjectStorageProperties.Qiniu qiniu = properties.getQiniu();
        requireText(qiniu.getAccessKey(), "j2agent.storage.qiniu.access-key");
        requireText(qiniu.getSecretKey(), "j2agent.storage.qiniu.secret-key");
        requireText(qiniu.getDomain(), "j2agent.storage.qiniu.domain");
        return new QiniuObjectStorageService(
                qiniu.getAccessKey(),
                qiniu.getSecretKey(),
                qiniu.getDomain(),
                qiniu.isUseHttps(),
                properties.getBucket(),
                new OkHttpClient()
        );
    }

    private S3Clients createS3Clients(ObjectStorageProperties.S3Compatible s3, String propertyPrefix) {
        requireText(s3.getEndpoint(), propertyPrefix + ".endpoint");
        requireText(s3.getAccessKeyId(), propertyPrefix + ".access-key-id");
        return createS3Clients(s3.getEndpoint(), s3.getAccessKeyId(), s3.getSecretAccessKey());
    }

    private S3Clients createS3Clients(String endpoint, String accessKeyId, String secretAccessKey) {
        requireText(secretAccessKey, "j2agent.storage.s3.secret-access-key");
        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        URI endpointUri = URI.create(endpoint);
        var credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
        );
        S3Client s3Client = S3Client.builder()
                .endpointOverride(endpointUri)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(s3Config)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(endpointUri)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(s3Config)
                .build();
        return new S3Clients(s3Client, presigner);
    }

    private void requireText(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing object storage property: " + propertyName);
        }
    }
}
