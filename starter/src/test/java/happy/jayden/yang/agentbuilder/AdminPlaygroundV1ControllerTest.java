package happy.jayden.yang.agentbuilder;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import happy.jayden.yang.agentbuilder.service.auth.AdminAuthService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminPlaygroundV1ControllerTest {

  @Mock private AdminAuthService auth;
  @Mock private AdminPlaygroundRunService runs;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new AdminPlaygroundV1Controller(auth, runs)).build();
  }

  @Test
  void acceptsAnyPublishedAgentKeyInsteadOfRejectingNonFitnessAgents() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant now = Instant.now();
    when(runs.start("baby.food", "晚饭吃什么"))
        .thenReturn(
            new AdminPlaygroundRunService.StartedRun(
                runId, conversationId, "baby.food", "RUNNING", now, now));

    mvc.perform(
            post("/api/v1/admin/playground/runs")
                .cookie(new jakarta.servlet.http.Cookie("AGENT_ADMIN_SESSION", "session"))
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"agentKey":"baby.food","input":"晚饭吃什么"}
                    """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.agentKey").value("baby.food"))
        .andExpect(jsonPath("$.runId").value(runId.toString()));

    verify(runs).start("baby.food", "晚饭吃什么");
  }
}
