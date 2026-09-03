package com.itmo.napoleonit.aiinterviewer.media

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * Presigned URL для прямой работы браузера с MinIO (Р-17).
 * Медиа через JVM не идёт.
 */
@Service
class S3Service(private val props: AppProperties) {

    private val presigner: S3Presigner = S3Presigner.builder()
        .region(Region.of(props.s3.region))
        .endpointOverride(URI.create(props.s3.publicEndpoint))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.s3.accessKey, props.s3.secretKey)
            )
        )
        // MinIO работает по path-style, иначе браузер пойдёт на bucket.localhost
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()

    fun presignUpload(key: String, contentType: String): String =
        presigner.presignPutObject { b ->
            b.signatureDuration(props.s3.uploadUrlTtl)
                .putObjectRequest(
                    PutObjectRequest.builder()
                        .bucket(props.s3.bucket)
                        .key(key)
                        .contentType(contentType)
                        .build()
                )
        }.url().toExternalForm()

    fun presignDownload(key: String): String =
        presigner.presignGetObject { b ->
            b.signatureDuration(props.s3.downloadUrlTtl)
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(props.s3.bucket)
                        .key(key)
                        .build()
                )
        }.url().toExternalForm()
}
