package com.agenttrail.loop.ppt.image;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

/**
 * issue #31 的核心落地：把文生图 API 返回的临时 URL 立即下载、转存进自建 MinIO，返回永久可访问的
 * URL——绝不持久化第三方那个临时链接本身（见 issue 描述：链接过期后历史 PPT 里的图就全部失效）。
 *
 * <p>bucket 首次使用时惰性创建 + 设成公开只读下载策略，不像 issue #23 图表那样要求运维提前手工
 * 建好——这个项目里 issue #23 的 MinIO 写入是外部 mcp-echarts 进程自己管自己的 bucket，这是第一次
 * 由这个 Java 进程的生产代码直接持有 MinIO 写权限，既然自己管，就把"能不能正常用"的前置条件也在
 * 自己这一层兜住，不额外制造一个"部署文档里要记得手工建 bucket"的隐藏依赖。
 */
public class MinioPptImageStore implements PptImageStore {

    private static final Logger log = LoggerFactory.getLogger(MinioPptImageStore.class);

    private final MinioClient minioClient;
    private final HttpClient downloadClient;
    private final String publicBaseUrl;
    private final String bucket;
    private final Duration timeout;

    private volatile boolean bucketReady = false;
    private final Object bucketLock = new Object();

    public MinioPptImageStore(String endpoint, String accessKey, String secretKey, String bucket, Duration timeout) {
        this.minioClient = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.downloadClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.publicBaseUrl = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
        this.timeout = timeout;
    }

    @Override
    public String downloadAndStore(String temporaryImageUrl, String objectKeyPrefix) {
        ensureBucketReady();
        TemporaryImageDownloader.DownloadedImage image =
                TemporaryImageDownloader.download(downloadClient, temporaryImageUrl, timeout);

        String objectKey = safePrefix(objectKeyPrefix) + "-" + UUID.randomUUID() + extensionFor(image.contentType());
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(image.bytes()), image.bytes().length, -1)
                    .contentType(image.contentType())
                    .build());
        } catch (Exception uploadFailed) {
            throw new PptImageException(
                    "上传图片到 MinIO 失败: bucket=" + bucket + " object=" + objectKey, uploadFailed);
        }
        String publicUrl = publicBaseUrl + "/" + bucket + "/" + objectKey;
        log.info("PPT 配图已转存至 MinIO: {}", publicUrl);
        return publicUrl;
    }

    private void ensureBucketReady() {
        if (bucketReady) {
            return;
        }
        synchronized (bucketLock) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(bucket)
                        .config(publicReadPolicy(bucket))
                        .build());
                bucketReady = true;
            } catch (Exception ensureFailed) {
                throw new PptImageException("初始化 MinIO bucket 失败: " + bucket, ensureFailed);
            }
        }
    }

    private static String publicReadPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": ["*"]},
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucket);
    }

    private static String safePrefix(String objectKeyPrefix) {
        if (objectKeyPrefix == null || objectKeyPrefix.isBlank()) {
            return "ppt-image";
        }
        // 会话 id 目前是自由文本，防一手混进 MinIO object key 不允许的字符
        return objectKeyPrefix.replaceAll("[^a-zA-Z0-9-_]", "_");
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return ".png";
        }
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }
}
