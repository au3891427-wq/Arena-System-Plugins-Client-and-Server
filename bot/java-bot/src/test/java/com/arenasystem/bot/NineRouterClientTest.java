package com.arenasystem.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NineRouterClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOpenAiCompatibleRequestAndExtractsAnswer() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"choices":[{"message":{"role":"assistant","content":"Halo dari 9Router."}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
                "test-model",
                10,
                new ObjectMapper()
        );

        String answer = client.answer("Apa itu ArenaSystem?", "Konteks dokumentasi.");

        assertEquals("Halo dari 9Router.", answer);
        assertEquals("Bearer test-secret", authorization.get());
        assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
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
                "test-model",
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
    void extractsTextPartsFromArrayContent() throws Exception {
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
                "test-model",
                10,
                new ObjectMapper()
        );

        assertEquals("Bagian jawaban.", client.answer("Pertanyaan", "Konteks"));
    }
}
