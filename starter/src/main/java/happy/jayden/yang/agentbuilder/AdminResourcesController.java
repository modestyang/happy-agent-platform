package happy.jayden.yang.agentbuilder;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.*;

import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistry;
import happy.jayden.yang.agentbuilder.service.auth.AdminAuthService;
import happy.jayden.yang.agentbuilder.service.workbench.AdminResourceService;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Resource-specific admin APIs; one bad catalog cannot prevent unrelated pages from loading. */
@RestController
@RequestMapping("/api/admin")
public class AdminResourcesController {
  private final AdminResourceService resources;
  private final AdminAuthService auth;
  private final ToolRegistry tools;

  public AdminResourcesController(
      AdminResourceService resources, AdminAuthService auth, ToolRegistry tools) {
    this.resources = resources;
    this.auth = auth;
    this.tools = tools;
  }

  @GetMapping("/providers")
  List<ProviderDefinition> providers(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
    authenticate(token);
    return resources.listProviders();
  }

  @PostMapping("/providers")
  ResponseEntity<ProviderDefinition> createProvider(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @RequestBody ProviderCreate request) {
    authenticate(token);
    var created = resources.createProvider(request);
    return ResponseEntity.created(URI.create("/api/admin/providers/" + created.providerKey()))
        .eTag(Long.toString(created.revision()))
        .body(created);
  }

  @PatchMapping("/providers/{providerKey}")
  ResponseEntity<ProviderDefinition> updateProvider(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @PathVariable("providerKey") String providerKey,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody ProviderUpdate request) {
    authenticate(token);
    var updated = resources.updateProvider(providerKey, request, revision(ifMatch));
    return ResponseEntity.ok().eTag(Long.toString(updated.revision())).body(updated);
  }

  @GetMapping("/models")
  List<ModelDefinition> models(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @RequestParam(value = "providerKey", required = false) String providerKey) {
    authenticate(token);
    return resources.listModels(providerKey);
  }

  @PostMapping("/models")
  ResponseEntity<ModelDefinition> createModel(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @RequestBody ModelCreate request) {
    authenticate(token);
    var created = resources.createModel(request);
    return ResponseEntity.created(URI.create("/api/admin/models/" + created.modelKey()))
        .eTag(Long.toString(created.revision()))
        .body(created);
  }

  @PatchMapping("/models/{modelKey}")
  ResponseEntity<ModelDefinition> updateModel(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @PathVariable("modelKey") String modelKey,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody ModelUpdate request) {
    authenticate(token);
    var updated = resources.updateModel(modelKey, request, revision(ifMatch));
    return ResponseEntity.ok().eTag(Long.toString(updated.revision())).body(updated);
  }

  @GetMapping("/prompts")
  List<PromptDefinition> prompts(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
    authenticate(token);
    return resources.listPrompts();
  }

  @PostMapping("/prompts")
  ResponseEntity<PromptDefinition> createPrompt(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody PromptCreate request) {
    authenticate(token);
    requireIdempotencyKey(idempotencyKey);
    var created = resources.createPrompt(request);
    return ResponseEntity.created(URI.create("/api/admin/prompts/" + created.promptKey()))
        .eTag(Long.toString(created.revision()))
        .body(created);
  }

  @PatchMapping("/prompts/{promptKey}")
  ResponseEntity<PromptDefinition> updatePrompt(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @PathVariable("promptKey") String promptKey,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody PromptUpdate request) {
    authenticate(token);
    var updated = resources.updatePrompt(promptKey, request, revision(ifMatch));
    return ResponseEntity.ok().eTag(Long.toString(updated.revision())).body(updated);
  }

  @GetMapping("/skills")
  List<SkillDefinition> skills(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
    authenticate(token);
    return resources.listSkills();
  }

  @PostMapping("/skills")
  ResponseEntity<SkillDefinition> createSkill(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody SkillCreate request) {
    authenticate(token);
    requireIdempotencyKey(idempotencyKey);
    var created = resources.createSkill(request);
    return ResponseEntity.created(URI.create("/api/admin/skills/" + created.skillKey()))
        .eTag(Long.toString(created.revision()))
        .body(created);
  }

  @PatchMapping("/skills/{skillKey}")
  ResponseEntity<SkillDefinition> updateSkill(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @PathVariable("skillKey") String skillKey,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody SkillUpdate request) {
    authenticate(token);
    var updated = resources.updateSkill(skillKey, request, revision(ifMatch));
    return ResponseEntity.ok().eTag(Long.toString(updated.revision())).body(updated);
  }

  @GetMapping("/hooks")
  List<HookDefinition> hooks(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
    authenticate(token);
    return resources.listHooks();
  }

  @PatchMapping("/hooks/{hookKey}")
  ResponseEntity<HookDefinition> updateHook(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
      @PathVariable("hookKey") String hookKey,
      @RequestHeader("If-Match") String ifMatch,
      @RequestBody HookUpdate request) {
    authenticate(token);
    var updated = resources.updateHook(hookKey, request, revision(ifMatch));
    return ResponseEntity.ok().eTag(Long.toString(updated.revision())).body(updated);
  }

  @GetMapping("/frameworks")
  List<FrameworkDefinition> frameworks(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
    authenticate(token);
    return resources.listFrameworks();
  }

  @GetMapping("/memories")
  List<MemoryDefinition> memories(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
    authenticate(token);
    return resources.listMemories();
  }

  @GetMapping("/tools")
  List<ToolDefinition> tools(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
    authenticate(token);
    return tools.descriptors().stream().map(this::tool).toList();
  }

  private ToolDefinition tool(ToolDescriptor descriptor) {
    return new ToolDefinition(
        descriptor.toolKey(),
        descriptor.contractVersion(),
        descriptor.runtimeName(),
        descriptor.displayName(),
        descriptor.description(),
        descriptor.whenToUse(),
        descriptor.whenNotToUse(),
        descriptor.sideEffect().name(),
        descriptor.riskLevel().name(),
        descriptor.requiredScopes(),
        descriptor.inputSchema().document(),
        descriptor.outputSchema().document());
  }

  private void authenticate(String token) {
    auth.authenticate(token);
  }

  private static long revision(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) throw new IllegalArgumentException("If-Match 必填");
    try {
      return Long.parseLong(ifMatch.trim().replace("W/", "").replace("\"", ""));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match 必须是有效 revision", exception);
    }
  }

  private static void requireIdempotencyKey(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Idempotency-Key 必填");
  }
}
