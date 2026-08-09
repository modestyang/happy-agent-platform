package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.service.auth.AdminAuthService;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Developer-only entry point for a real Agent runtime probe. */
@RestController
@RequestMapping("/api/admin/playground")
public class AdminPlaygroundController {
  private final AdminAuthService auth;
  private final FitnessApplicationService fitness;
  private final PublishedAgentPlaygroundRuntime runtime;

  public AdminPlaygroundController(
      AdminAuthService auth,
      FitnessApplicationService fitness,
      PublishedAgentPlaygroundRuntime runtime) {
    this.auth = auth;
    this.fitness = fitness;
    this.runtime = runtime;
  }

  @PostMapping("/messages")
  DebugMessageResponse send(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody DebugMessageRequest request) {
    auth.authenticate(sessionToken);
    if (request == null || request.agentKey() == null || request.agentKey().isBlank()) {
      throw new IllegalArgumentException("agentKey 必填");
    }
    String message = request.message();
    String agentKey = request.agentKey().trim();
    if ("fitness.coach".equals(agentKey)) {
      return new DebugMessageResponse(fitness.sendAiMessageForDeveloper(message).message());
    }
    return new DebugMessageResponse(runtime.send(agentKey, message));
  }

  public record DebugMessageRequest(String agentKey, String message) {}

  public record DebugMessageResponse(String message) {}
}
