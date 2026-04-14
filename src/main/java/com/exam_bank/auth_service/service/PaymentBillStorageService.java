package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.MinioProperties;
import com.exam_bank.auth_service.exception.StorageUnavailableException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentBillStorageService {

    private static final long MAX_BILL_SIZE_BYTES = 20L * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    private final AtomicBoolean bucketInitialized = new AtomicBoolean(false);

    public String uploadBill(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Bill image is required");
        }
        if (file.getSize() > MAX_BILL_SIZE_BYTES) {
            throw new IllegalArgumentException("Bill image size must be less than or equal to 20MB");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Bill file must be an image");
        }

        String objectKey = buildBillObjectKey(userId, file.getOriginalFilename());
        String bucketName = minioProperties.getBucketName();
        String endpoint = minioProperties.getUrl();
        ensureBucketExists();

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
            return buildFileUrl(objectKey);
        } catch (Exception exception) {
            log.error(
                    "Failed to upload bill image to MinIO: userId={}, bucket={}, endpoint={}, objectKey={}, contentType={}, size={}B, fileName={}, cause={}",
                    userId,
                    bucketName,
                    endpoint,
                    objectKey,
                    contentType,
                    file.getSize(),
                    file.getOriginalFilename(),
                    exception.getMessage(),
                    exception);
            throw new StorageUnavailableException("File storage is temporarily unavailable", exception);
        }
    }

    public BillFileContent downloadBillByUrl(String billImageUrl) {
        if (!StringUtils.hasText(billImageUrl)) {
            throw new IllegalArgumentException("Bill image URL is required");
        }

        String bucketName = minioProperties.getBucketName();
        String objectKey = resolveObjectKey(billImageUrl, bucketName);

        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build())) {
            String contentType = normalizeContentType(minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build()).contentType());
            byte[] content = inputStream.readAllBytes();
            return new BillFileContent(content, contentType, objectKey);
        } catch (Exception exception) {
            log.error(
                    "Failed to read bill image from MinIO: bucket={}, endpoint={}, objectKey={}, sourceUrl={}, cause={}",
                    bucketName,
                    minioProperties.getUrl(),
                    objectKey,
                    billImageUrl,
                    exception.getMessage(),
                    exception);
            throw new StorageUnavailableException("Bill image is temporarily unavailable", exception);
        }
    }

    private void ensureBucketExists() {
        if (bucketInitialized.get()) {
            return;
        }

        synchronized (bucketInitialized) {
            if (bucketInitialized.get()) {
                return;
            }
            try {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .build());

                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .build());
                }

                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .config(buildPublicReadPolicy(minioProperties.getBucketName()))
                        .build());

                bucketInitialized.set(true);
            } catch (Exception exception) {
                log.error("Failed to initialize MinIO bucket: bucket={}, endpoint={}, cause={}",
                        minioProperties.getBucketName(),
                        minioProperties.getUrl(),
                        exception.getMessage(),
                        exception);
                throw new StorageUnavailableException("File storage is temporarily unavailable", exception);
            }
        }
    }

    private String buildBillObjectKey(Long userId, String originalFilename) {
        String extension = extractExtension(originalFilename);
        return "subscription-bills/user-" + userId + "/" + UUID.randomUUID() + extension;
    }

    private String resolveObjectKey(String sourceUrl, String bucketName) {
        String candidate = sourceUrl.trim();
        try {
            URI uri = new URI(candidate);
            if (StringUtils.hasText(uri.getPath())) {
                candidate = uri.getPath();
            }
        } catch (Exception ignored) {
            // Keep original string when URI parsing fails.
        }

        candidate = candidate.replaceFirst("^/+", "");
        if (!StringUtils.hasText(candidate)) {
            throw new IllegalArgumentException("Bill image URL is invalid");
        }

        String bucketPrefix = bucketName + "/";
        if (candidate.startsWith(bucketPrefix)) {
            return candidate.substring(bucketPrefix.length());
        }

        return candidate;
    }

    private String extractExtension(String originalFilename) {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        if (!StringUtils.hasText(extension)) {
            return ".bin";
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String buildFileUrl(String objectKey) {
        String endpoint = minioProperties.getUrl().replaceAll("/+$", "");
        return endpoint + "/" + minioProperties.getBucketName() + "/" + objectKey;
    }

    private String buildPublicReadPolicy(String bucketName) {
        return "{" +
                "\"Version\":\"2012-10-17\"," +
                "\"Statement\":[{" +
                "\"Effect\":\"Allow\"," +
                "\"Principal\":{\"AWS\":[\"*\"]}," +
                "\"Action\":[\"s3:GetObject\"]," +
                "\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]" +
                "}]" +
                "}";
    }

    public record BillFileContent(byte[] content, String contentType, String objectKey) {
    }
}