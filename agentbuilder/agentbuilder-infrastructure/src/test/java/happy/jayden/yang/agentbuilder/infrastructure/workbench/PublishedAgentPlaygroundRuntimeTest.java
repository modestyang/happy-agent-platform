package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.ProviderUpdate;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.CreateAgentRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PublishedAgentPlaygroundRuntimeTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private HttpServer modelServer;
  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcAdminWorkbenchStore workbench;
  private JdbcAdminResourceStore resources;
  private JdbcRunTraceRepository traces;
  private Path masterKey;

  @BeforeEach
  void setUp() throws Exception {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    try (var connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V1__agent_baseline.sql"));
    }
    var mapper = new ObjectMapper().findAndRegisterModules();
    masterKey = Files.createTempFile("happy-agent-generic-runtime", ".key");
    Files.writeString(
        masterKey, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
    workbench = new JdbcAdminWorkbenchStore(dataSource, mapper, masterKey);
    resources = new JdbcAdminResourceStore(dataSource, mapper);
    traces = new JdbcRunTraceRepository(dataSource);

    modelServer = HttpServer.create(new InetSocketAddress(0), 0);
    modelServer.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              ("data: {\"choices\":[{\"delta\":{\"content\":\"今晚吃\"}}]}\n\n"
                      + "data: {\"choices\":[{\"delta\":{\"content\":\"清淡一些。\"}}]}\n\n"
                      + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}\n\n"
                      + "data: [DONE]\n\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    modelServer.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (modelServer != null) modelServer.stop(0);
    Files.deleteIfExists(masterKey);
  }

  @Test
  void streamsAnyPublishedAgentAndPersistsItsRunConversationAndTrace() throws Exception {
    var provider =
        resources.listProviders().stream()
            .filter(item -> item.providerKey().equals("minimax"))
            .findFirst()
            .orElseThrow();
    resources.updateProvider(
        "minimax",
        new ProviderUpdate(
            provider.displayName(),
            "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1",
            "ACTIVE"),
        provider.revision());
    workbench.saveCredential("minimax", "sk-test-generic-agent".toCharArray());
    var draft = workbench.createDraft(new CreateAgentRequest("baby.food", "辅食助手", "为家庭提供辅食安排建议"));
    workbench.publish(draft);

    var runtime =
        new PublishedAgentPlaygroundRuntime(
            dataSource, new ObjectMapper().findAndRegisterModules(), masterKey, traces);

    var started = runtime.startStreaming("baby.food", "晚饭吃什么", Runnable::run);

    assertEquals("baby.food", started.agentKey());
    assertEquals(1, started.agentVersion());
    var trace = traces.findTrace(started.runId()).orElseThrow();
    assertEquals("SUCCEEDED", trace.status());
    assertEquals("今晚吃清淡一些。", trace.outputSummary());
    assertTrue(
        traces.streamEventsAfter(started.runId(), 0).stream()
            .anyMatch(
                event ->
                    event.type().equals("TEXT_DELTA") && event.data().get("delta").equals("今晚吃")));
    assertEquals(
        "今晚吃清淡一些。",
        traces
            .findConversation(started.conversationId())
            .orElseThrow()
            .messages()
            .get(1)
            .content());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM agent_runs WHERE run_id=? AND agent_key='baby.food'",
            Integer.class,
            started.runId()));
  }
}
