package io.github.jerryt92.j2agent.config.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "j2agent.storage")
public class ObjectStorageProperties {
    private boolean enabled;
    private StorageType type = StorageType.S3;
    private String bucket;
    private S3Compatible s3 = new S3Compatible();
    private AliyunOss aliyunOss = new AliyunOss();
    private Qiniu qiniu = new Qiniu();
    private Sync sync = new Sync();
    private Upload upload = new Upload();
    private Delete delete = new Delete();
    /**
     * 对象存储文件访问方式（预览/下载/直传）：
     * {@link AccessMode#DIRECT} OSS 预签名直链；
     * {@link AccessMode#PROXY} 经应用服务器同源 content 代理。
     */
    private AccessMode accessMode = AccessMode.PROXY;

    public enum AccessMode {
        /** OSS 预签名直链 */
        DIRECT,
        /** 经应用服务器 content 端点转发，不依赖预签名 host */
        PROXY
    }

    public enum StorageType {
        S3,
        ALIYUN_OSS,
        QINIU
    }

    @Getter
    @Setter
    public static class S3Compatible {
        private String endpoint;
        private String accessKeyId;
        private String secretAccessKey;
    }

    @Getter
    @Setter
    public static class AliyunOss {
        private String endpoint;
        private String accessKeyId;
        private String accessKeySecret;
    }

    @Getter
    @Setter
    public static class Qiniu {
        private String accessKey;
        private String secretKey;
        private String domain;
        private boolean useHttps = true;
    }

    @Getter
    @Setter
    public static class Sync {
        private int retentionDays = 7;
        private String cleanupCron = "0 30 2 * * *";
    }

    @Getter
    @Setter
    public static class Upload {
        private Reconcile reconcile = new Reconcile();

        @Getter
        @Setter
        public static class Reconcile {
            private boolean enabled = true;
            /** 最多对账次数；与 {@link #retryDelaySeconds} 相乘为 abandoned 上传的默认等待窗口。 */
            private int maxAttempts = 10;
            /** 每次对账失败后的固定重试间隔（秒），上传对账不使用指数退避。 */
            private int retryDelaySeconds = 30;
            /**
             * 对账总窗口上限（秒），仅作文档/校验参考。
             * 默认 {@code maxAttempts × retryDelaySeconds}（10×30=300）。
             */
            private int maxTotalSeconds = 300;
            private int heartbeatIntervalSeconds = 10;
            private int heartbeatTtlSeconds = 30;
            private int inProgressDelaySeconds = 10;
        }
    }

    @Getter
    @Setter
    public static class Delete {
        private Reconcile reconcile = new Reconcile();

        @Getter
        @Setter
        public static class Reconcile {
            private boolean enabled = true;
            private int maxAttempts = 20;
            private int initialDelaySeconds = 10;
            private int maxDelaySeconds = 300;
        }
    }
}
