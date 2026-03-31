package com.sheepfold.personalaiworkspaceagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.Proxy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LlamaParseDocumentLoader {

    public static final String SOURCE_FILE_NAME_METADATA_KEY = "source_file_name";
    public static final String SOURCE_PARSER_METADATA_KEY = "source_parser";

    private static final ProxySelector NO_PROXY_SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // no-op
        }
    };

    private final RestClient systemRestClient;
    private final RestClient directRestClient;
    private final HttpClient systemUploadHttpClient;
    private final HttpClient directUploadHttpClient;
    private final boolean preferDirectConnection;
    private final String normalizedBaseUrl;
    private final long uploadReadTimeoutMs;
    private final ObjectMapper objectMapper;
    private final MarkdownChunkSplitter markdownChunkSplitter;
    private final String apiKey;
    private final String tier;
    private final String version;
    private final long pollIntervalMs;
    private final long pollTimeoutMs;

    public LlamaParseDocumentLoader(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${rag.llamaparse.base-url:https://api.cloud.llamaindex.ai}") String baseUrl,
            @Value("${rag.llamaparse.api-key:}") String apiKey,
            @Value("${rag.llamaparse.tier:fast}") String tier,
            @Value("${rag.llamaparse.version:latest}") String version,
            @Value("${rag.llamaparse.disable-system-proxy:true}") boolean disableSystemProxy,
            @Value("${rag.llamaparse.connect-timeout-ms:15000}") long connectTimeoutMs,
            @Value("${rag.llamaparse.read-timeout-ms:300000}") long readTimeoutMs,
            @Value("${rag.llamaparse.poll-interval-ms:1500}") long pollIntervalMs,
            @Value("${rag.llamaparse.poll-timeout-ms:120000}") long pollTimeoutMs,
            @Value("${rag.chunking.chunk-size:800}") int chunkSize,
            @Value("${rag.chunking.min-chunk-size-chars:200}") int minChunkSizeChars,
            @Value("${rag.chunking.min-chunk-length-to-embed:20}") int minChunkLengthToEmbed,
            @Value("${rag.chunking.max-num-chunks:10000}") int maxNumChunks,
            @Value("${rag.chunking.keep-separator:true}") boolean keepSeparator) {
        int resolvedConnectTimeout = (int) Math.max(1000, connectTimeoutMs);
        int resolvedReadTimeout = (int) Math.max(1000, readTimeoutMs);

        SimpleClientHttpRequestFactory systemRequestFactory = new SimpleClientHttpRequestFactory();
        systemRequestFactory.setConnectTimeout(resolvedConnectTimeout);
        systemRequestFactory.setReadTimeout(resolvedReadTimeout);
        this.systemRestClient = restClientBuilder
                .requestFactory(systemRequestFactory)
                .baseUrl(baseUrl)
                .build();

        SimpleClientHttpRequestFactory directRequestFactory = new SimpleClientHttpRequestFactory();
        directRequestFactory.setConnectTimeout(resolvedConnectTimeout);
        directRequestFactory.setReadTimeout(resolvedReadTimeout);
        directRequestFactory.setProxy(Proxy.NO_PROXY);
        this.directRestClient = restClientBuilder
                .requestFactory(directRequestFactory)
                .baseUrl(baseUrl)
                .build();

        this.systemUploadHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(resolvedConnectTimeout))
                .build();

        this.directUploadHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(resolvedConnectTimeout))
                .proxy(NO_PROXY_SELECTOR)
                .build();

        this.preferDirectConnection = disableSystemProxy;
        this.normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        this.uploadReadTimeoutMs = resolvedReadTimeout;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.tier = tier;
        this.version = version;
        this.pollIntervalMs = pollIntervalMs;
        this.pollTimeoutMs = pollTimeoutMs;
        this.markdownChunkSplitter = new MarkdownChunkSplitter(
                chunkSize,
                minChunkSizeChars,
                minChunkLengthToEmbed,
                maxNumChunks,
                keepSeparator);
    }

    public List<Document> loadAndSplit(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return List.of();
        }

        String fileName = sanitizeFileName(file.getOriginalFilename());
        String markdown = parseToMarkdown(file);
        if (!StringUtils.hasText(markdown)) {
            return List.of();
        }

        Map<String, Object> baseMetadata = new LinkedHashMap<>();
        baseMetadata.put(SOURCE_FILE_NAME_METADATA_KEY, fileName);
        baseMetadata.put(SOURCE_PARSER_METADATA_KEY, "llamaparse");

        List<String> chunks = markdownChunkSplitter.split(markdown);

        List<Document> normalizedChunks = new ArrayList<>();
        int totalChunks = chunks.size();
        int chunkIndex = 0;
        for (String chunkText : chunks) {
            if (!StringUtils.hasText(chunkText)) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
            metadata.put(SOURCE_FILE_NAME_METADATA_KEY, fileName);
            metadata.put(SOURCE_PARSER_METADATA_KEY, "llamaparse");
            metadata.put("chunk_index", chunkIndex);
            metadata.put("total_chunks", totalChunks);

            normalizedChunks.add(new Document(chunkText, metadata));
            chunkIndex++;
        }

        return normalizedChunks;
    }

    private String parseToMarkdown(MultipartFile file) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("LLAMA_CLOUD_API_KEY is required for LlamaParse ingestion.");
        }

        Map<String, Object> uploadResponse = upload(file);
        String markdownFromUpload = extractMarkdown(uploadResponse);
        if (StringUtils.hasText(markdownFromUpload)) {
            return markdownFromUpload;
        }

        String jobId = extractJobId(uploadResponse);
        if (!StringUtils.hasText(jobId)) {
            throw new IllegalStateException("LlamaParse upload response does not contain a parse job id.");
        }

        return pollMarkdown(jobId);
    }

    private Map<String, Object> upload(MultipartFile file) {
        MultipartUploadPayload payload = buildMultipartPayload(file);
        Map<String, Object> response = uploadWithRouteFailover(payload);

        return response == null ? Map.of() : response;
    }

    private Map<String, Object> uploadWithRouteFailover(MultipartUploadPayload payload) {
        HttpClient primaryClient = preferDirectConnection ? directUploadHttpClient : systemUploadHttpClient;
        HttpClient fallbackClient = preferDirectConnection ? systemUploadHttpClient : directUploadHttpClient;

        try {
            return uploadOnce(primaryClient, payload);
        } catch (UploadHttpException httpException) {
            if (httpException.getStatusCode() != 499) {
                throw new IllegalStateException(
                        "LlamaParse upload failed with HTTP " + httpException.getStatusCode()
                                + ". Response: " + abbreviateBody(httpException.getResponseBody()),
                        httpException);
            }
            return retryUploadWithFallback(payload, fallbackClient, httpException);
        } catch (UploadTransportException networkException) {
            return retryUploadWithFallback(payload, fallbackClient, networkException);
        }
    }

    private Map<String, Object> retryUploadWithFallback(
            MultipartUploadPayload payload,
            HttpClient fallbackClient,
            Exception firstFailure) {
        try {
            return uploadOnce(fallbackClient, payload);
        } catch (UploadHttpException fallbackHttpException) {
            if (fallbackHttpException.getStatusCode() == 499) {
                IllegalStateException wrapped = new IllegalStateException(
                        "LlamaParse upload was interrupted (HTTP 499). "
                                + "Both direct and proxy routes were attempted. "
                                + "Try adjusting rag.llamaparse.disable-system-proxy based on your network.",
                        fallbackHttpException);
                wrapped.addSuppressed(firstFailure);
                throw wrapped;
            }

            IllegalStateException wrapped = new IllegalStateException(
                    "LlamaParse upload failed with HTTP " + fallbackHttpException.getStatusCode()
                            + ". Response: " + abbreviateBody(fallbackHttpException.getResponseBody()),
                    fallbackHttpException);
            wrapped.addSuppressed(firstFailure);
            throw wrapped;
        } catch (UploadTransportException fallbackNetworkException) {
            IllegalStateException wrapped = new IllegalStateException(
                    "LlamaParse upload failed due to network timeout/connection issues on both routes. "
                            + "Try adjusting rag.llamaparse.disable-system-proxy and increasing rag.llamaparse.read-timeout-ms.",
                    fallbackNetworkException);
            wrapped.addSuppressed(firstFailure);
            throw wrapped;
        }
    }

    private Map<String, Object> uploadOnce(HttpClient client, MultipartUploadPayload payload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl + "/api/v2/parse/upload"))
                .timeout(Duration.ofMillis(uploadReadTimeoutMs))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + payload.boundary())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload.body()))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new UploadTransportException("Interrupted while uploading document to LlamaParse.",
                    interruptedException);
        } catch (IOException ioException) {
            throw new UploadTransportException("Failed to upload document to LlamaParse.", ioException);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new UploadHttpException(status, response.body());
        }

        return parseUploadResponse(response.body());
    }

    private MultipartUploadPayload buildMultipartPayload(MultipartFile file) {
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded file bytes.", e);
        }

        String boundary = "----LlamaParseBoundary" + UUID.randomUUID().toString().replace("-", "");
        String safeFileName = sanitizeFileName(file.getOriginalFilename()).replace("\"", "\\\"");
        String configurationJson = buildConfigurationJson();

        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);

        byte[] suffix = ("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"configuration\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + configurationJson + "\r\n"
                + "--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);

        byte[] body = new byte[prefix.length + fileBytes.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(fileBytes, 0, body, prefix.length, fileBytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + fileBytes.length, suffix.length);

        return new MultipartUploadPayload(boundary, body);
    }

    private Map<String, Object> parseUploadResponse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to parse LlamaParse upload response: " + abbreviateBody(responseBody),
                    e);
        }
    }

    private String abbreviateBody(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "<empty>";
        }

        String singleLine = responseBody.replaceAll("\\s+", " ").trim();
        int maxLength = 300;
        if (singleLine.length() <= maxLength) {
            return singleLine;
        }
        return singleLine.substring(0, maxLength) + "...";
    }

    private String normalizeBaseUrl(String baseUrl) {
        String cleaned = StringUtils.trimWhitespace(baseUrl);
        if (!StringUtils.hasText(cleaned)) {
            throw new IllegalStateException("rag.llamaparse.base-url must not be empty.");
        }
        if (cleaned.endsWith("/")) {
            return cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private record MultipartUploadPayload(String boundary, byte[] body) {
    }

    private static final class UploadHttpException extends RuntimeException {

        private final int statusCode;
        private final String responseBody;

        private UploadHttpException(int statusCode, String responseBody) {
            super("LlamaParse upload failed with HTTP status " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        private int getStatusCode() {
            return statusCode;
        }

        private String getResponseBody() {
            return responseBody;
        }
    }

    private static final class UploadTransportException extends RuntimeException {

        private UploadTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private RestClient primaryClient() {
        return preferDirectConnection ? directRestClient : systemRestClient;
    }

    private String pollMarkdown(String jobId) {
        long deadline = System.currentTimeMillis() + pollTimeoutMs;

        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> result = fetchJobResult(jobId);
            String status = extractStatus(result);

            if (isCompletedStatus(status)) {
                String markdown = extractMarkdown(result);
                if (StringUtils.hasText(markdown)) {
                    return markdown;
                }
                throw new IllegalStateException("LlamaParse completed but returned empty markdown output.");
            }

            if (isFailedStatus(status)) {
                throw new IllegalStateException("LlamaParse parse failed. status=" + status + ", message="
                        + extractErrorMessage(result));
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for LlamaParse result.", e);
            }
        }

        throw new IllegalStateException("Timeout waiting for LlamaParse result.");
    }

    private Map<String, Object> fetchJobResult(String jobId) {
        RestClient client = primaryClient();
        try {
            Map<String, Object> result = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v2/parse/{jobId}/result")
                            .queryParam("include_markdown", supportsMarkdownExpansion())
                            .queryParam("include_text", true)
                            .build(jobId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            return result == null ? Map.of() : result;
        } catch (HttpClientErrorException.NotFound notFound) {
            return fetchJobResultFromV2(jobId);
        }
    }

    private Map<String, Object> fetchJobResultFromV2(String jobId) {
        RestClient client = primaryClient();
        try {
            Map<String, Object> fallback = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v2/parse/{jobId}")
                            .queryParam("expand", buildExpandParam())
                            .build(jobId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            return fallback == null ? Map.of() : fallback;
        } catch (HttpClientErrorException.BadRequest badRequest) {
            if (!supportsMarkdownExpansion()) {
                throw badRequest;
            }

            Map<String, Object> textOnlyFallback = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v2/parse/{jobId}")
                            .queryParam("expand", "text,metadata,job_metadata")
                            .build(jobId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            return textOnlyFallback == null ? Map.of() : textOnlyFallback;
        }
    }

    private String buildExpandParam() {
        if (supportsMarkdownExpansion()) {
            return "markdown,text,metadata,job_metadata";
        }
        return "text,metadata,job_metadata";
    }

    private boolean supportsMarkdownExpansion() {
        return !"FAST".equalsIgnoreCase(tier);
    }

    private String buildConfigurationJson() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("tier", tier);
        configuration.put("version", version);

        Map<String, Object> outputOptions = new LinkedHashMap<>();
        outputOptions.put("markdown", new LinkedHashMap<>());
        configuration.put("output_options", outputOptions);

        try {
            return objectMapper.writeValueAsString(configuration);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize LlamaParse configuration.", e);
        }
    }

    private String extractJobId(Map<String, Object> payload) {
        String id = asString(payload.get("id"));
        if (StringUtils.hasText(id)) {
            return id;
        }

        String jobId = asString(payload.get("job_id"));
        if (StringUtils.hasText(jobId)) {
            return jobId;
        }

        Map<String, Object> job = asMap(payload.get("job"));
        if (!job.isEmpty()) {
            String nestedId = asString(job.get("id"));
            if (StringUtils.hasText(nestedId)) {
                return nestedId;
            }
        }

        return "";
    }

    private String extractStatus(Map<String, Object> payload) {
        String status = asString(payload.get("status"));
        if (StringUtils.hasText(status)) {
            return status.toUpperCase(Locale.ROOT);
        }

        Map<String, Object> metadata = asMap(payload.get("metadata"));
        String metadataStatus = asString(metadata.get("status"));
        if (StringUtils.hasText(metadataStatus)) {
            return metadataStatus.toUpperCase(Locale.ROOT);
        }

        Map<String, Object> job = asMap(payload.get("job"));
        String jobStatus = asString(job.get("status"));
        if (StringUtils.hasText(jobStatus)) {
            return jobStatus.toUpperCase(Locale.ROOT);
        }

        return "";
    }

    private String extractErrorMessage(Map<String, Object> payload) {
        String message = asString(payload.get("error_message"));
        if (StringUtils.hasText(message)) {
            return message;
        }

        Map<String, Object> job = asMap(payload.get("job"));
        String jobMessage = asString(job.get("error_message"));
        if (StringUtils.hasText(jobMessage)) {
            return jobMessage;
        }

        return "";
    }

    private boolean isCompletedStatus(String status) {
        return "SUCCESS".equals(status) || "COMPLETED".equals(status);
    }

    private boolean isFailedStatus(String status) {
        return "FAILED".equals(status) || "ERROR".equals(status) || "CANCELLED".equals(status);
    }

    private String extractMarkdown(Map<String, Object> payload) {
        String markdown = extractMarkdownNode(payload.get("markdown"));
        if (StringUtils.hasText(markdown)) {
            return markdown;
        }

        Map<String, Object> result = asMap(payload.get("result"));
        if (!result.isEmpty()) {
            String fromResultMarkdown = extractMarkdownNode(result.get("markdown"));
            if (StringUtils.hasText(fromResultMarkdown)) {
                return fromResultMarkdown;
            }

            String fromResultText = extractTextNode(result.get("text"));
            if (StringUtils.hasText(fromResultText)) {
                return fromResultText;
            }
        }

        String textFallback = extractTextNode(payload.get("text"));
        return textFallback == null ? "" : textFallback;
    }

    private String extractMarkdownNode(Object markdownNode) {
        if (markdownNode instanceof String markdownText) {
            return markdownText;
        }

        Map<String, Object> markdownMap = asMap(markdownNode);
        if (markdownMap.isEmpty()) {
            return "";
        }

        String directMarkdown = asString(markdownMap.get("markdown"));
        if (StringUtils.hasText(directMarkdown)) {
            return directMarkdown;
        }

        return joinPageContent(markdownMap.get("pages"), "markdown");
    }

    private String extractTextNode(Object textNode) {
        if (textNode instanceof String text) {
            return text;
        }

        Map<String, Object> textMap = asMap(textNode);
        if (textMap.isEmpty()) {
            return "";
        }

        String directText = asString(textMap.get("text"));
        if (StringUtils.hasText(directText)) {
            return directText;
        }

        return joinPageContent(textMap.get("pages"), "text");
    }

    private String joinPageContent(Object pagesNode, String contentKey) {
        if (!(pagesNode instanceof List<?> pages) || pages.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (Object page : pages) {
            Map<String, Object> pageMap = asMap(page);
            String content = asString(pageMap.get(contentKey));
            if (StringUtils.hasText(content)) {
                parts.add(content);
            }
        }

        return String.join("\n\n", parts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "uploaded-file";
        }
        return StringUtils.cleanPath(fileName);
    }
}
