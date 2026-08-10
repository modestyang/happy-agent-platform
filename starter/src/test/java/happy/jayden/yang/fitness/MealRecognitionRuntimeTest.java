package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MealRecognitionRuntimeTest {
  @Test
  void sendsBailianCompatibleVisionRequestWithStrictSchemaAndParsesCompletion() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              "{\"choices\":[{\"message\":{\"content\":\"<think>inspect the image</think>\\n```json\\n{\\\"items\\\":[{\\\"name\\\":\\\"rice\\\",\\\"estimatedKcal\\\":200,\\\"confidence\\\":0.8}]}\\n```\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      ObjectMapper mapper = new ObjectMapper();
      var ds = new DriverManagerDataSource("jdbc:postgresql://localhost:1/not-used");
      var runtime = new MealRecognitionRuntime(ds, ds, mapper, Path.of("build/no-key").toString());
      JsonNode parsed =
          runtime.post(
              new MealRecognitionRuntime.RuntimeConfig(
                  "bailian", "qwen-vl", "http://localhost:" + server.getAddress().getPort()),
              "secret".toCharArray(),
              new MealRecognitionRuntime.Image("image/png", new byte[] {1, 2, 3}));

      JsonNode request = mapper.readTree(body.get());
      assertThat(request.path("model").asText()).isEqualTo("qwen-vl");
      assertThat(request.path("max_tokens").asInt()).isEqualTo(1000);
      assertThat(request.path("messages").get(0).path("content").get(0).path("text").asText())
          .contains("items", "name", "estimatedKcal", "confidence");
      assertThat(
              request
                  .path("messages")
                  .get(0)
                  .path("content")
                  .get(1)
                  .path("image_url")
                  .path("url")
                  .asText())
          .startsWith("data:image/png;base64,");
      assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean())
          .isTrue();
      assertThat(
              request
                  .path("response_format")
                  .path("json_schema")
                  .path("schema")
                  .path("properties")
                  .path("items")
                  .path("items")
                  .path("properties")
                  .has("confidence"))
          .isTrue();
      assertThat(
              request
                  .path("response_format")
                  .path("json_schema")
                  .path("schema")
                  .path("properties")
                  .path("items")
                  .path("items")
                  .path("properties")
                  .path("name")
                  .path("maxLength")
                  .asInt())
          .isEqualTo(120);
      assertThat(parsed.path("items").get(0).path("estimatedKcal").asInt()).isEqualTo(200);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void convertsAClosedSchemaViolationIntoInvalidModelResponse() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var ds = new DriverManagerDataSource("jdbc:postgresql://localhost:1/not-used");
    var runtime = new MealRecognitionRuntime(ds, ds, mapper, Path.of("build/no-key").toString());

    var result =
        runtime.result(
            mapper.readTree(
                """
                {"items":[{"name":"rice","estimatedKcal":"200","confidence":0.8,"extra":true}]}
                """));

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.failureCode()).isEqualTo("INVALID_MODEL_RESPONSE");
  }

  @Test
  void acceptsA120CharacterCandidateNameAndRejects121Characters() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var ds = new DriverManagerDataSource("jdbc:postgresql://localhost:1/not-used");
    var runtime = new MealRecognitionRuntime(ds, ds, mapper, Path.of("build/no-key").toString());

    String atLimit = "🍅".repeat(120);
    assertThat(
            runtime
                .result(
                    mapper.readTree(
                        "{\"items\":[{\"name\":\"%s\",\"estimatedKcal\":200,\"confidence\":0.8}]}"
                            .formatted(atLimit)))
                .status())
        .isEqualTo("SUCCEEDED");
    assertThat(
            runtime
                .result(
                    mapper.readTree(
                        "{\"items\":[{\"name\":\"%s\",\"estimatedKcal\":200,\"confidence\":0.8}]}"
                            .formatted("🍅".repeat(121))))
                .failureCode())
        .isEqualTo("INVALID_MODEL_RESPONSE");
  }
}
