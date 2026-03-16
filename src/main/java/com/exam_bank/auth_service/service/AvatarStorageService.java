package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class AvatarStorageService {

    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    private final AtomicBoolean bucketInitialized = new AtomicBoolean(false);

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
}
