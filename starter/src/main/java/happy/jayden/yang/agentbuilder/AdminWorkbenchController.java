package happy.jayden.yang.agentbuilder;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.AgentDraftView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.DraftUpdate;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.ProviderView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.PublicationView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.RunView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.ValidationView;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.WorkbenchSnapshot;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchService;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import java.util.Arrays;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminWorkbenchController {
  private final AdminWorkbenchService workbench;
  private final FitnessApplicationService fitness;

  public AdminWorkbenchController(
      AdminWorkbenchService workbench, FitnessApplicationService fitness) {
    this.workbench = workbench;
    this.fitness = fitness;
  }

  @GetMapping("/workbench")
  WorkbenchSnapshot snapshot(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
    authenticate(sessionToken);
    return workbench.snapshot();
  }

  @PatchMapping("/agents/{agentKey}/draft")
  ResponseEntity<AgentDraftView> updateDraft(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("agentKey") String agentKey,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody DraftUpdate update) {
    authenticate(sessionToken);
    var revised = workbench.updateDraft(agentKey, update, revision(ifMatch));
    return ResponseEntity.ok().eTag(Long.toString(revised.revision())).body(revised);
  }

  @PostMapping("/agents/{agentKey}/validate")
  ValidationView validate(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("agentKey") String agentKey) {
    authenticate(sessionToken);
    return workbench.validate(agentKey);
  }

  @PostMapping("/agents/{agentKey}/publish")
  PublicationView publish(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("agentKey") String agentKey) {
    authenticate(sessionToken);
    return workbench.publish(agentKey);
  }

  @PutMapping("/providers/{providerKey}/credential")
  ProviderView saveCredential(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
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

  @GetMapping("/runs/{runId}")
  RunView run(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("runId") UUID runId) {
    authenticate(sessionToken);
    return workbench.run(runId);
  }

  private void authenticate(String sessionToken) {
    fitness.authenticateSession(sessionToken);
  }

  private static long revision(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) throw new IllegalArgumentException("If-Match 必填");
    var normalized = ifMatch.trim().replace("W/", "").replace("\"", "");
    try {
      return Long.parseLong(normalized);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match 必须是草稿 revision", exception);
    }
  }

  public record CredentialRequest(char[] apiKey) {}
}
