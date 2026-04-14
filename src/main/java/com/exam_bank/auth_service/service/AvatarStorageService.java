package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.MinioProperties;
import com.exam_bank.auth_service.exception.StorageUnavailableException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
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
public class AvatarStorageService {

    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    private final AtomicBoolean bucketInitialized = new AtomicBoolean(false);

    public String toPublicAvatarUrl(Long userId, String avatarUrl) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(avatarUrl)) {
            return null;
        }

        String normalized = avatarUrl.trim();
        if (!isManagedAvatarObject(normalized, minioProperties.getBucketName())) {
            return normalized;
        }

        return "/api/v1/auth/users/" + userId + "/avatar";
    }

    public String uploadAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Avatar file is required");
        }

        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new IllegalArgumentException("Avatar file size must be less than or equal to 5MB");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Avatar file must be an image");
        }

        String objectKey = buildAvatarObjectKey(userId, file.getOriginalFilename());
        ensureBucketExists();

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(contentType)
                    .build());

            return buildFileUrl(objectKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to upload avatar file", exception);
        }
    }

    public AvatarFileContent downloadAvatarByUrl(String avatarUrl) {
        if (!StringUtils.hasText(avatarUrl)) {
            throw new IllegalArgumentException("Avatar URL is required");
        }

        String bucketName = minioProperties.getBucketName();
        String objectKey = resolveObjectKey(avatarUrl, bucketName);
        if (!isManagedAvatarObject(objectKey, bucketName)) {
            throw new IllegalArgumentException("Avatar URL is not managed by storage service");
        }

        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build())) {
            String contentType = normalizeContentType(minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build()).contentType());
            byte[] content = inputStream.readAllBytes();
            return new AvatarFileContent(content, contentType, objectKey);
        } catch (Exception exception) {
            log.error(
                    "Failed to read avatar from MinIO: bucket={}, endpoint={}, objectKey={}, sourceUrl={}, cause={}",
                    bucketName,
                    minioProperties.getUrl(),
                    objectKey,
                    avatarUrl,
                    exception.getMessage(),
                    exception);
            throw new StorageUnavailableException("Avatar image is temporarily unavailable", exception);
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

                // Make avatar objects readable by browser via direct URL.
                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .config(buildPublicReadPolicy(minioProperties.getBucketName()))
                        .build());

                bucketInitialized.set(true);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to initialize MinIO bucket", exception);
            }
        }
    }

    private String buildAvatarObjectKey(Long userId, String originalFilename) {
        String extension = extractExtension(originalFilename);
        return "avatars/user-" + userId + "/" + UUID.randomUUID() + extension;
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

    private String resolveObjectKey(String sourceUrl, String bucketName) {
        String candidate = sourceUrl.trim();
        try {
            URI uri = new URI(candidate);
            if (StringUtils.hasText(uri.getPath())) {
                candidate = uri.getPath();
            }
        } catch (Exception ignored) {
            // Keep original source when URI parsing fails.
        }

        candidate = candidate.replaceFirst("^/+", "");
        if (!StringUtils.hasText(candidate)) {
            throw new IllegalArgumentException("Avatar URL is invalid");
        }

        String bucketPrefix = bucketName + "/";
        if (candidate.startsWith(bucketPrefix)) {
            return candidate.substring(bucketPrefix.length());
        }

        return candidate;
    }

    private boolean isManagedAvatarObject(String source, String bucketName) {
        if (!StringUtils.hasText(source)) {
            return false;
        }

        String candidate = source.trim();
        try {
            URI uri = new URI(candidate);
            if (StringUtils.hasText(uri.getPath())) {
                candidate = uri.getPath();
            }
        } catch (Exception ignored) {
            // Keep original source when URI parsing fails.
        }

        candidate = candidate.replaceFirst("^/+", "");
        if (!StringUtils.hasText(candidate)) {
            return false;
        }

        String bucketPrefix = bucketName + "/";
        if (candidate.startsWith(bucketPrefix)) {
            candidate = candidate.substring(bucketPrefix.length());
        }

        return candidate.startsWith("avatars/");
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

    public record AvatarFileContent(byte[] content, String contentType, String objectKey) {
    }
}
