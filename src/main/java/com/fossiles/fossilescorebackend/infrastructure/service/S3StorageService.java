package com.fossiles.fossilescorebackend.infrastructure.service;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3StorageService {

    @Value("${app.s3.bucket:}")
    private String bucket;

    @Value("${app.s3.prefix:uploads/}")
    private String prefix;

    @Value("${app.s3.region:}")
    private String region;

    @Value("${app.s3.access-key-id:}")
    private String accessKeyId;

    @Value("${app.s3.secret-access-key:}")
    private String secretAccessKey;

    @Value("${app.s3.enabled:true}")
    private boolean s3Enabled;

    public UploadResult uploadImage(MultipartFile file) throws IOException {
        validateConfig();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío");
        }

        String extension = extractExtension(file.getOriginalFilename());
        String key = buildKey("images/", extension);

        S3ClientBuilder s3Builder = S3Client.builder()
                .region(Region.of(region));

        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
            s3Builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }

        S3Client s3 = s3Builder.build();

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .build();

        s3.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        s3.close();

        String url = String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);
        return UploadResult.builder()
                .key(key)
                .url(url)
                .uploadedAt(Instant.now())
                .build();
    }

    public UploadResult uploadPDF(MultipartFile file) throws IOException {
        validateConfig();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío");
        }

        if (!file.getContentType().equals("application/pdf")) {
            throw new IllegalArgumentException("El archivo debe ser un PDF");
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!extension.equalsIgnoreCase(".pdf")) {
            extension = ".pdf";
        }
        // Nota: En S3 no es necesario crear la carpeta "pdfs/" antes de subir.
        // S3 crea automáticamente la estructura de "carpetas" basándose en los prefijos de las keys.
        // Al subir un objeto con key "uploads/pdfs/[UUID].pdf", S3 automáticamente organiza el archivo.
        String key = buildKey("pdfs/", extension);

        S3ClientBuilder s3Builder = S3Client.builder()
                .region(Region.of(region));

        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
            s3Builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }

        S3Client s3 = s3Builder.build();

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/pdf")
                .build();

        s3.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        s3.close();

        String url = String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);
        return UploadResult.builder()
                .key(key)
                .url(url)
                .uploadedAt(Instant.now())
                .build();
    }

    public UploadResult uploadDOCX(MultipartFile file) throws IOException {
        validateConfig();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío");
        }

        String contentType = file.getContentType();
        if (contentType == null || 
            (!contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document") 
             && !contentType.equals("application/msword"))) {
            throw new IllegalArgumentException("El archivo debe ser un documento Word (.docx o .doc)");
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!extension.equalsIgnoreCase(".docx") && !extension.equalsIgnoreCase(".doc")) {
            extension = ".docx";
        }
        // Nota: En S3 no es necesario crear la carpeta "docx/" antes de subir.
        // S3 crea automáticamente la estructura de "carpetas" basándose en los prefijos de las keys.
        String key = buildKey("docx/", extension);

        S3ClientBuilder s3Builder = S3Client.builder()
                .region(Region.of(region));

        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
            s3Builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }

        S3Client s3 = s3Builder.build();

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();

        s3.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        s3.close();

        String url = String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);
        return UploadResult.builder()
                .key(key)
                .url(url)
                .uploadedAt(Instant.now())
                .build();
    }

    public DownloadResult downloadByUrl(String url) throws IOException {
        validateConfig();
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("URL vacía");
        }

        String key = extractS3KeyFromUrl(url);
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("No se pudo resolver la key de S3 desde la URL");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL inválida");
        }

        String host = uri.getHost();
        String targetBucket = bucket;
        String clientRegion = region;

        Optional<String> bucketFromVirtualHost = extractBucketFromVirtualHostedStyle(host);
        if (bucketFromVirtualHost.isPresent()) {
            targetBucket = bucketFromVirtualHost.get();
            Optional<String> regionFromHost = extractRegionFromS3VirtualHost(host);
            if (regionFromHost.isPresent()) {
                clientRegion = regionFromHost.get();
            }
        } else {
            ParsedPathStyleS3 pathStyle = tryParsePathStyle(uri);
            if (pathStyle != null) {
                targetBucket = pathStyle.bucket();
                key = pathStyle.key();
                if (StringUtils.hasText(pathStyle.region())) {
                    clientRegion = pathStyle.region();
                }
            }
        }

        S3ClientBuilder s3Builder = S3Client.builder()
                .region(Region.of(clientRegion));
        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
            s3Builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }
        S3Client s3 = s3Builder.build();
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(targetBucket)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(getRequest)) {
            GetObjectResponse resp = stream.response();
            byte[] bytes = stream.readAllBytes();
            return DownloadResult.builder()
                    .key(key)
                    .contentType(resp.contentType())
                    .contentLength(resp.contentLength())
                    .bytes(bytes)
                    .build();
        } finally {
            s3.close();
        }
    }

    private String extractS3KeyFromUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            ParsedPathStyleS3 pathStyle = tryParsePathStyle(uri);
            if (pathStyle != null) {
                return pathStyle.key();
            }
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) return "";
            String cleaned = path.startsWith("/") ? path.substring(1) : path;
            // For URLs like https://bucket.s3.region.amazonaws.com/<key>
            return cleaned;
        } catch (URISyntaxException ex) {
            return "";
        }
    }

    /**
     * Host virtual-hosted: {@code bucket.s3.region.amazonaws.com} (y variantes .dualstack, etc.)
     */
    private Optional<String> extractBucketFromVirtualHostedStyle(String host) {
        if (!StringUtils.hasText(host)) {
            return Optional.empty();
        }
        int dotS3 = host.indexOf(".s3.");
        if (dotS3 <= 0) {
            return Optional.empty();
        }
        return Optional.of(host.substring(0, dotS3));
    }

    private Optional<String> extractRegionFromS3VirtualHost(String host) {
        if (!StringUtils.hasText(host)) {
            return Optional.empty();
        }
        int start = host.indexOf(".s3.");
        if (start < 0) {
            return Optional.empty();
        }
        int afterS3 = start + 4;
        int end = host.indexOf(".amazonaws.com", afterS3);
        if (end <= afterS3) {
            return Optional.empty();
        }
        String maybe = host.substring(afterS3, end);
        // dualstack: bucket.s3.dualstack.us-east-2.amazonaws.com
        if (maybe.startsWith("dualstack.")) {
            return Optional.of(maybe.substring("dualstack.".length()));
        }
        return StringUtils.hasText(maybe) ? Optional.of(maybe) : Optional.empty();
    }

    private record ParsedPathStyleS3(String bucket, String key, String region) {}

    /**
     * Path-style: {@code https://s3.region.amazonaws.com/bucket/key} o {@code https://s3.amazonaws.com/bucket/key}
     */
    private ParsedPathStyleS3 tryParsePathStyle(URI uri) {
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return null;
        }
        boolean pathStyleHost = "s3.amazonaws.com".equalsIgnoreCase(host)
                || host.startsWith("s3.")
                        && (host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn"));
        if (!pathStyleHost) {
            return null;
        }
        String path = uri.getPath();
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return null;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash >= trimmed.length() - 1) {
            return null;
        }
        String b = trimmed.substring(0, slash);
        String k = trimmed.substring(slash + 1);
        if (!StringUtils.hasText(b) || !StringUtils.hasText(k)) {
            return null;
        }
        String r = region;
        if (host.startsWith("s3.") && !"s3.amazonaws.com".equalsIgnoreCase(host)) {
            // s3.us-east-2.amazonaws.com
            String after = host.substring("s3.".length());
            int dotAmz = after.indexOf(".amazonaws");
            if (dotAmz > 0) {
                r = after.substring(0, dotAmz);
            }
        }
        return new ParsedPathStyleS3(b, k, r);
    }

    private void validateConfig() {
        if (!s3Enabled) {
            throw new IllegalStateException("S3 está deshabilitado. Configura app.s3.enabled=true para habilitarlo.");
        }
        
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException(
                "app.s3.bucket no está configurado. " +
                "Configura el bucket de S3 en application.properties o como variable de entorno."
            );
        }
        
        if (!StringUtils.hasText(region)) {
            throw new IllegalStateException(
                "app.s3.region no está configurado. " +
                "Configura la región de AWS (ej: us-east-2) en application.properties o como variable de entorno."
            );
        }
        
        if (!StringUtils.hasText(prefix)) {
            prefix = "uploads/";
        }
        
        // Validar que las credenciales estén disponibles (explícitas o por defecto)
        boolean hasExplicitCredentials = StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey);
        boolean hasEnvCredentials = StringUtils.hasText(System.getenv("AWS_ACCESS_KEY_ID")) 
                                    && StringUtils.hasText(System.getenv("AWS_SECRET_ACCESS_KEY"));
        
        if (!hasExplicitCredentials && !hasEnvCredentials) {
            // No lanzar error aquí, permitir que AWS SDK use credenciales por defecto
            // (IAM role, ~/.aws/credentials, etc.)
            // Solo loguear una advertencia si es necesario
        }
    }

    private String buildKey(String extension) {
        return buildKey("", extension);
    }

    /**
     * Construye la key para S3 con el prefijo, subcarpeta y extensión.
     * En S3, las "carpetas" son solo prefijos en las keys, no necesitan crearse explícitamente.
     * 
     * @param subfolder Subcarpeta (ej: "images/", "pdfs/") - puede ser vacío
     * @param extension Extensión del archivo (ej: ".jpg", ".pdf")
     * @return Key completa para S3 (ej: "uploads/pdfs/uuid.pdf")
     */
    private String buildKey(String subfolder, String extension) {
        String cleanPrefix = prefix;
        if (!cleanPrefix.endsWith("/")) {
            cleanPrefix = cleanPrefix + "/";
        }
        String folder = cleanPrefix + subfolder;
        return folder + UUID.randomUUID() + extension;
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String ext = StringUtils.getFilenameExtension(filename);
        return ext != null ? "." + ext : "";
    }

    @Getter
    @Builder
    public static class UploadResult {
        private final String key;
        private final String url;
        private final Instant uploadedAt;
    }

    @Getter
    @Builder
    public static class DownloadResult {
        private final String key;
        private final String contentType;
        private final Long contentLength;
        private final byte[] bytes;
    }
}

