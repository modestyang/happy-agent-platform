package happy.jayden.yang.agentbuilder;

import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunPage;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunQuery;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunTrace;

import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.AgentDraftView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.ComponentUpdate;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.ComponentView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.DraftUpdate;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.ProviderView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.PublicationView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.ValidationView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.WorkbenchSnapshot;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchService;
import happy.jayden.yang.fitness.LocalAuthController;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Personal workbench API: catalog maintenance, release control, and real-run trace lookup. */
@RestController
@RequestMapping("/api/admin")
public class AdminWorkbenchController {
  private final AdminWorkbenchService workbench;
  private final FitnessApplicationService fitness;
  private final JdbcRunTraceRepository runTraces;

  public AdminWorkbenchController(
      AdminWorkbenchService workbench,
      FitnessApplicationService fitness,
      JdbcRunTraceRepository runTraces) {
    this.workbench = workbench;
    this.fitness = fitness;
    this.runTraces = runTraces;
  }

  @GetMapping("/workbench")
  WorkbenchSnapshot snapshot(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false)
          String sessionToken) {
    authenticate(sessionToken);
    return workbench.snapshot();
  }

  @PatchMapping("/agents/{agentKey}/draft")
  ResponseEntity<AgentDraftView> updateDraft(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("agentKey") String agentKey,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody DraftUpdate update) {
    authenticate(sessionToken);
    var revised = workbench.updateDraft(agentKey, update, revision(ifMatch));
    return ResponseEntity.ok().eTag(Long.toString(revised.revision())).body(revised);
  }

  @PatchMapping("/components/{type}/{componentKey}")
  ComponentView updateComponent(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("type") String type,
      @PathVariable("componentKey") String componentKey,
      @RequestBody ComponentUpdate request) {
    authenticate(sessionToken);
    return workbench.updateComponent(type, componentKey, request);
  }

  @PostMapping("/agents/{agentKey}/validate")
  ValidationView validate(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("agentKey") String agentKey) {
    authenticate(sessionToken);
    return workbench.validate(agentKey);
  }

  @PostMapping("/agents/{agentKey}/publish")
  PublicationView publish(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("agentKey") String agentKey) {
    authenticate(sessionToken);
    return workbench.publish(agentKey);
  }

  @PutMapping("/providers/{providerKey}/credential")
  ProviderView saveCredential(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("providerKey") String providerKey,
      @RequestBody CredentialRequest request) {
    authenticate(sessionToken);
    if (request == null || request.apiKey() == null)
      throw new IllegalArgumentException("apiKey 必填");
    try {
      return workbench.saveCredential(providerKey, request.apiKey());
    } finally {
      Arrays.fill(request.apiKey(), '\0');
    }
  }

  /** The Playground calls the normal app runtime; this endpoint exposes its persisted trace. */
  @GetMapping("/runs")
  RunPage listRuns(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @RequestParam(value = "agent", required = false) String agentKey,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(value = "to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "1") int size,
      @RequestParam(value = "sort", defaultValue = "started_at,desc") String sort) {
    authenticate(sessionToken);
    return runTraces.list(new RunQuery(agentKey, status, from, to, page, size, sort));
  }

  @GetMapping("/runs/{runId}")
  RunTrace runTrace(
      @CookieValue(name = LocalAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("runId") UUID runId) {
    authenticate(sessionToken);
    return runTraces.findTrace(runId).orElseThrow(() -> new IllegalArgumentException("运行记录不存在"));
  }

  private void authenticate(String sessionToken) {
    fitness.authenticateSession(sessionToken);
  }

  private static long revision(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) throw new IllegalArgumentException("If-Match 必填");
    try {
      return Long.parseLong(ifMatch.trim().replace("W/", "").replace("\"", ""));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match 必须是草稿 revision", exception);
    }
  }

  public record CredentialRequest(char[] apiKey) {}
}
