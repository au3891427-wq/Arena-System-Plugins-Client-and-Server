package com.arenasystem.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NineRouterClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsStreamingOpenAiRequestAndExtractsSseAnswer() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    data: {"id":"chatcmpl-test","choices":[{"index":0,"delta":{"content":"Halo "}}]}

                    data: {"id":"chatcmpl-test","choices":[{"index":0,"delta":{"content":"dari 9Router."}}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        NineRouterClient client = new NineRouterClient(
                baseUrl,
                "test-secret",
                "Telegram_Bot",
                10,
                new ObjectMapper()
        );

        String answer = client.answer("Apa itu ArenaSystem?", "Konteks dokumentasi.");

        assertEquals("Halo dari 9Router.", answer);
        assertEquals("Bearer test-secret", authorization.get());
        assertTrue(accept.get().contains("text/event-stream"));
        JsonNode requestJson = new ObjectMapper().readTree(requestBody.get());
        assertEquals("Telegram_Bot", requestJson.path("model").asText());
        assertTrue(requestJson.path("stream").asBoolean());
        assertTrue(requestBody.get().contains("Apa itu ArenaSystem?"));
        assertTrue(requestBody.get().contains("Konteks dokumentasi."));
    }

    @Test
    void rejectsNonSuccessfulGatewayResponsesWithoutExposingBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "sensitive upstream error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        NineRouterClient client = new NineRouterClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-secret",
                "Telegram_Bot",
                10,
                new ObjectMapper()
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> client.answer("Pertanyaan", "Konteks")
        );
        assertEquals("9Router returned HTTP 401.", exception.getMessage());
        assertTrue(!exception.getMessage().contains("sensitive"));
    }

    @Test
    void reportsSafeModelNotFoundDetailsForHttpErrors() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    {"error":{"message":"The model Telegram_Bot does not exist.","type":"invalid_request_error","code":"model_not_found"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        NineRouterClient client = new NineRouterClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-secret",
                "Telegram_Bot",
                10,
                new ObjectMapper()
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> client.answer("Pertanyaan", "Konteks")
        );
        assertEquals(
                "9Router returned HTTP 404 (model_not_found: The model Telegram_Bot does not exist.) " +
                        "(model_catalog_http_status=404).",
                exception.getMessage()
        );
    }

    @Test
    void doesNotExposeArbitraryUpstreamErrorMessages() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    {"error":{"message":"Sensitive prompt echo: do not log this text or token=secret-value","code":"bad_request"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        NineRouterClient client = new NineRouterClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-secret",
                "Telegram_Bot",
                10,
                new ObjectMapper()
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> client.answer("Pertanyaan", "Konteks")
        );
        assertEquals("9Router returned HTTP 400 (bad_request).", exception.getMessage());
        assertTrue(!exception.getMessage().contains("secret-value"));
        assertTrue(!exception.getMessage().contains("Sensitive prompt"));
    }

    @Test
    void checksCloudModelCatalogWhenCompletionReturnsNotFound() throws Exception {
        AtomicReference<String> modelsAuthorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"error":{"message":"No active credentials for provider: test-provider","type":"invalid_request_error","code":"model_not_found"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/v1/models", exchange -> {
            modelsAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"object":"list","data":[
                      {"id":"Telegram_Bot","object":"model","owned_by":"combo"},
                      {"id":"test-provider/test-model","object":"model","owned_by":"test-provider"},
                      {"id":"backup-combo","object":"model","owned_by":"combo"}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        NineRouterClient client = new NineRouterClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-secret",
                "Telegram_Bot",
                10,
                new ObjectMapper()
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> client.answer("Pertanyaan", "Konteks")
        );

        assertEquals("Bearer test-secret", modelsAuthorization.get());
        assertTrue(exception.getMessage().contains("model_in_catalog=true"));
        assertTrue(exception.getMessage().contains("combo_count=2"));
    }

    @Test
    void reportsWhenConfiguredComboIsMissingFromCloudModelCatalog() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/v1/models", exchange -> {
            byte[] body = """
                    {"object":"list","data":[
                      {"id":"backup-combo","object":"model","owned_by":"combo"}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        NineRouterClient client = new NineRouterClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-secret",
                "Telegram_Bot",
                10,
                new ObjectMapper()
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> client.answer("Pertanyaan", "Konteks")
        );

        assertTrue(exception.getMessage().contains("model_in_catalog=false"));
        assertTrue(exception.getMessage().contains("combo_count=1"));
    }

    @Test
    void extractsTextPartsFromJsonArrayContent() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    {"choices":[{"message":{"content":[{"type":"text","text":"Bagian "},{"type":"text","text":"jawaban."}]}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        NineRouterClient client = new NineRouterClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-secret",
                "Telegram_Bot",
                10,
                new ObjectMapper()
        );

        assertEquals("Bagian jawaban.", client.answer("Pertanyaan", "Konteks"));
    }
}
