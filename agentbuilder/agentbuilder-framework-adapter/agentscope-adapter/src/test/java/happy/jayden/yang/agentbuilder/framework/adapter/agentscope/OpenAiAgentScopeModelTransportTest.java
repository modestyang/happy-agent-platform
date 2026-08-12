package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiAgentScopeModelTransportTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void sendsThePublishedModelIdAndBearerCredentialToAnOpenAiCompatibleEndpoint() throws Exception {
    var authorization = new AtomicReference<String>();
    var requestBody = new AtomicReference<String>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              ("data: {\"id\":\"reply-1\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                      + "\"model\":\"MiniMax-M3\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                      + "data: [DONE]\n\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    var endpoint =
        new RunRequest.ModelEndpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
            "MiniMax-M3",
            new RunRequest.ModelCredential("test-token".toCharArray()));
    var transport = new OpenAiAgentScopeModelTransport(endpoint);

    var replies =
        transport.stream(
                List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()),
                List.of(),
                GenerateOptions.builder().stream(true).build())
            .collectList()
            .block();

    assertEquals("Bearer test-token", authorization.get());
    assertEquals("MiniMax-M3", mapper.readTree(requestBody.get()).path("model").asText());
    assertTrue(mapper.readTree(requestBody.get()).path("stream").asBoolean());
    assertTrue(replies != null && !replies.isEmpty());
  }

  @Test
  void removesMiniMaxThinkingMarkupAcrossStreamingChunkBoundaries() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              (streamingTextChunk("reply-1", "<thi")
                      + streamingTextChunk("reply-1", "nk>private reasoning one</think>\n")
                      + streamingTextChunk("reply-1", "<think>private reasoning two</thi")
                      + streamingTextChunk("reply-1", "nk>\n\n今晚这样吃")
                      + streamingTextChunk("reply-1", "：蛋白一掌")
                      + streamingTextChunk("reply-1", "<think>format check</think>\n\n蔬菜两拳。")
                      + "data: [DONE]\n\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    var endpoint =
        new RunRequest.ModelEndpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
            "MiniMax-M3",
            new RunRequest.ModelCredential("test-token".toCharArray()));
    var transport = new OpenAiAgentScopeModelTransport(endpoint);

    var replies =
        transport.stream(
                List.of(Msg.builder().role(MsgRole.USER).textContent("今晚吃什么").build()),
                List.of(),
                GenerateOptions.builder().stream(true).build())
            .collectList()
            .block();

    String visibleText =
        replies.stream()
            .flatMap(reply -> reply.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .collect(Collectors.joining());
    assertEquals("今晚这样吃：蛋白一掌\n\n蔬菜两拳。", visibleText);
  }

  @Test
  void flushesOrdinaryTextThatEndsLikeAnOpeningThinkingTag() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              (streamingTextChunk("reply-1", "普通正文结尾 <th") + "data: [DONE]\n\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    var endpoint =
        new RunRequest.ModelEndpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
            "MiniMax-M3",
            new RunRequest.ModelCredential("test-token".toCharArray()));
    var transport = new OpenAiAgentScopeModelTransport(endpoint);

    var replies =
        transport.stream(
                List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()),
                List.of(),
                GenerateOptions.builder().stream(true).build())
            .collectList()
            .block();

    String visibleText =
        replies.stream()
            .flatMap(reply -> reply.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .collect(Collectors.joining());
    assertEquals("普通正文结尾 <th", visibleText);
  }

  private String streamingTextChunk(String replyId, String content) throws java.io.IOException {
    String encodedContent = mapper.writeValueAsString(content);
    return "data: {\"id\":\""
        + replyId
        + "\",\"object\":\"chat.completion.chunk\",\"created\":0,"
        + "\"model\":\"MiniMax-M3\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"
        + encodedContent
        + "},\"finish_reason\":null}]}\n\n";
  }
}
