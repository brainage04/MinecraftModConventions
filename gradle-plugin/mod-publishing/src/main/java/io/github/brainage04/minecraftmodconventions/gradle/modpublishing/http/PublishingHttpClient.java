package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.http;

import org.gradle.api.GradleException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PublishingHttpClient {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String apiEndpoint;
    private final String token;
    private final String userAgent;
    private final int maxRetries;

    public PublishingHttpClient(String apiEndpoint, String token, String userAgent, int maxRetries) {
        this.apiEndpoint = apiEndpoint.replaceAll("/+$", "");
        this.token = token;
        this.userAgent = userAgent;
        this.maxRetries = Math.max(0, maxRetries);
    }

    public Response get(String path) {
        return send("GET", path, null, null);
    }

    public Response patchJson(String path, String json) {
        return send("PATCH", path, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public Response postJson(String path, String json) {
        return send("POST", path, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public Response patchBytes(String path, String contentType, byte[] bytes) {
        return send("PATCH", path, contentType, bytes);
    }

    public Response postMultipart(String path, MultipartBody body) {
        return send("POST", path, body.contentType(), body.bytes());
    }

    private Response send(String method, String path, String contentType, byte[] body) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiEndpoint + path))
                        .timeout(Duration.ofMinutes(2))
                        .header("Authorization", token)
                        .header("User-Agent", userAgent);
                if (contentType != null) {
                    request.header("Content-Type", contentType);
                }
                request.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
                HttpResponse<String> response = client.send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (!retryable(response.statusCode()) || attempt == maxRetries) {
                    return new Response(response.statusCode(), response.body());
                }
            } catch (IOException exception) {
                lastFailure = new GradleException("Publication request failed: " + method + " " + path, exception);
                if (attempt == maxRetries) {
                    throw lastFailure;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GradleException("Publication request was interrupted: " + method + " " + path, exception);
            }
            try {
                Thread.sleep(Math.min(1_000L, 100L << Math.min(attempt, 3)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GradleException("Publication retry was interrupted", exception);
            }
        }
        throw lastFailure == null ? new GradleException("Publication request failed") : lastFailure;
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    public static MultipartBody multipartJson(String json, String filePart, String fileName, String contentType, byte[] file) {
        String boundary = "minecraft-mod-conventions-" + UUID.randomUUID();
        Map<String, Part> parts = new LinkedHashMap<>();
        parts.put("data", new Part(null, "application/json", json.getBytes(StandardCharsets.UTF_8)));
        if (file != null) {
            parts.put(filePart, new Part(fileName, contentType, file));
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Map.Entry<String, Part> entry : parts.entrySet()) {
                Part part = entry.getValue();
                output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                String disposition = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"";
                if (part.fileName() != null) {
                    disposition += "; filename=\"" + part.fileName().replace("\"", "") + "\"";
                }
                output.write((disposition + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(("Content-Type: " + part.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(part.bytes());
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return new MultipartBody("multipart/form-data; boundary=" + boundary, output.toByteArray());
        } catch (IOException exception) {
            throw new GradleException("Failed to create multipart publication request", exception);
        }
    }

    public record Response(int status, String body) {
    }

    public record MultipartBody(String contentType, byte[] bytes) {
    }

    private record Part(String fileName, String contentType, byte[] bytes) {
    }
}
