package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import happy.jayden.yang.agentbuilder.core.defaults.EffectiveValueSource;
import happy.jayden.yang.agentbuilder.core.defaults.ModelParameters;
import happy.jayden.yang.agentbuilder.core.defaults.PublishedResolvedConfigSources;
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import happy.jayden.yang.agentbuilder.core.defaults.RuntimeLimits;
import happy.jayden.yang.agentbuilder.core.runtime.AgentFrameworkRegistry;
import happy.jayden.yang.agentbuilder.core.runtime.AssistantReply;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.runtime.RuntimeHookExecutor;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistry;
import happy.jayden.yang.agentbuilder.infrastructure.security.AesGcmCredentialCipher;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationMessage;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationSummary;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Executes an immutable published Agent configuration through its selected framework adapter. */
public final class PublishedAgentPlaygroundRuntime {
  private static final UUID DEVELOPER_USER_ID =
      UUID.nameUUIDFromBytes("happy-agent:developer-playground".getBytes(StandardCharsets.UTF_8));
  private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> TRUSTED_EXERCISE_NAME_TOOL_KEYS =
      Set.of("fitness.exercise.candidates.query", "fitness.exercise.details.query");
  private static final String PUBLISHED_AGENT_SQL =
      "SELECT version,configuration::text FROM agent_versions WHERE agent_key=?"
          + " AND status='PUBLISHED' ORDER BY version DESC LIMIT 1";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Path masterKeyFile;
  private final JdbcRunTraceRepository traces;
  private final AgentFrameworkRegistry frameworks;
  private final ToolRegistry tools;
  private final RuntimeHookExecutor hookExecutor;

  public PublishedAgentPlaygroundRuntime(
      DataSource dataSource,
      ObjectMapper mapper,
      Path masterKeyFile,
      JdbcRunTraceRepository traces,
      AgentFrameworkRegistry frameworks,
      ToolRegistry tools,
      RuntimeHookExecutor hookExecutor) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.masterKeyFile = Objects.requireNonNull(masterKeyFile, "masterKeyFile").toAbsolutePath();
    this.traces = Objects.requireNonNull(traces, "traces");
    this.frameworks = Objects.requireNonNull(frameworks, "frameworks");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.hookExecutor = Objects.requireNonNull(hookExecutor, "hookExecutor");
  }

  public String send(String agentKey, String message) {
    if (agentKey == null || agentKey.isBlank()) throw new IllegalArgumentException("agentKey 必填");
    if (message == null || message.isBlank()) throw new IllegalArgumentException("message 不能为空");
    RuntimeConfig config = load(agentKey.trim());
    Instant startedAt = Instant.now();
    UUID runId = UUID.randomUUID();
    var conversation = traces.resolveConversation(DEVELOPER_USER_ID, config.agentKey(), startedAt);
    List<ConversationMessage> history =
        traces.recentConversationMessages(conversation.conversationId(), 20);
    traces.insertRun(
        runId,
        DEVELOPER_USER_ID,
        conversation.conversationId(),
        config.agentKey(),
        config.version(),
        config.frameworkKey(),
        config.modelKey(),
        truncate(message, 1000));
    traces.appendConversationMessage(
        conversation.conversationId(), runId, "USER", message.trim(), startedAt);

    try {
      var outcome =
          runAdapter(config, runId, DEVELOPER_USER_ID, message.trim(), history, event -> {});
      String response = outcome.response();
      Instant completedAt = Instant.now();
      long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();
      traces.markCompleted(
          runId,
          "SUCCEEDED",
          completedAt,
          durationMs,
          outcome.toolCalls(),
          0,
          0,
          0,
          config.modelKey(),
          null,
          null,
          truncate(response, 1500));
      traces.appendConversationMessage(
          conversation.conversationId(), runId, "ASSISTANT", response, completedAt);
      return response;
    } catch (RuntimeException exception) {
      failure(runId, conversation.conversationId(), exception.getMessage());
      throw new PlaygroundRuntimeUnavailableException(
          "所选 Agent 运行失败：" + safeMessage(exception), exception);
    }
  }

  /** Runs one published Skill as an isolated, non-conversational background task. */
  public TaskRunResult runTask(
      String agentKey, UUID userId, String requiredSkillKey, String input) {
    if (agentKey == null || agentKey.isBlank()) throw new IllegalArgumentException("agentKey 必填");
    Objects.requireNonNull(userId, "userId");
    if (requiredSkillKey == null || requiredSkillKey.isBlank()) {
      throw new IllegalArgumentException("requiredSkillKey 必填");
    }
    if (input == null || input.isBlank()) throw new IllegalArgumentException("input 不能为空");

    RuntimeConfig config;
    PublishedSkill selectedSkill;
    try {
      config = load(agentKey.trim());
      selectedSkill = requiredTaskSkill(config, requiredSkillKey.trim());
      validateTaskTools(config, selectedSkill);
    } catch (RuntimeException exception) {
      throw new TaskConfigurationException(safeMessage(exception), exception);
    }

    Instant startedAt = Instant.now();
    UUID runId = UUID.randomUUID();
    String conversationAgentKey = config.agentKey() + ":background:" + selectedSkill.skill().key();
    var conversation = traces.resolveConversation(userId, conversationAgentKey, startedAt);
    traces.insertRun(
        runId,
        userId,
        conversation.conversationId(),
        config.agentKey(),
        config.version(),
        config.frameworkKey(),
        config.modelKey(),
        truncate(input, 1000));
    traces.appendConversationMessage(
        conversation.conversationId(), runId, "USER", input.trim(), startedAt);

    try {
      var outcome =
          runAdapter(
              config,
              runId,
              userId,
              input.trim(),
              selectedSkill.requiredToolKeys().stream().sorted().toList(),
              List.of(selectedSkill.skill()),
              List.of(),
              "fitness.background-task:" + selectedSkill.skill().key(),
              event -> {});
      if (outcome.pendingApproval() != null) {
        throw new PlaygroundRuntimeUnavailableException("后台任务不允许请求写操作确认");
      }
      Instant completedAt = Instant.now();
      traces.markCompleted(
          runId,
          "SUCCEEDED",
          completedAt,
          Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli()),
          outcome.toolCalls(),
          0,
          0,
          0,
          config.modelKey(),
          null,
          null,
          truncate(outcome.response(), 1500));
      traces.appendConversationMessage(
          conversation.conversationId(), runId, "ASSISTANT", outcome.response(), completedAt);
      return new TaskRunResult(
          runId,
          config.agentKey(),
          config.version(),
          selectedSkill.skill().key(),
          selectedSkill.revision(),
          outcome.response());
    } catch (RuntimeException exception) {
      failure(runId, conversation.conversationId(), exception.getMessage());
      throw new TaskExecutionException(safeMessage(exception), exception);
    }
  }

  /** Starts a durable streamed run for any immutable published Agent snapshot. */
  public StreamingRun startStreaming(String agentKey, String message, Executor executor) {
    return startStreaming(agentKey, DEVELOPER_USER_ID, message, false, executor);
  }

  /** Starts a durable streamed run under the authenticated caller that owns its conversation. */
  public StreamingRun startStreaming(
      String agentKey, UUID userId, String message, Executor executor) {
    return startStreaming(agentKey, userId, message, false, executor);
  }

  /** Starts a durable streamed run, optionally closing the caller's current conversation first. */
  public StreamingRun startStreaming(
      String agentKey, UUID userId, String message, boolean newConversation, Executor executor) {
    RuntimeConfig config = requireStreamingInput(agentKey, userId, message, executor);
    Instant startedAt = Instant.now();
    var conversation =
        newConversation
            ? traces.startNewConversation(userId, config.agentKey(), startedAt)
            : traces.resolveConversation(userId, config.agentKey(), startedAt);
    return startStreaming(config, userId, message.trim(), conversation, startedAt, executor);
  }

  /** Creates the conversation before the client sends a message and returns its opaque id. */
  public CreatedConversation createConversation(String agentKey, UUID userId) {
    if (agentKey == null || agentKey.isBlank()) throw new IllegalArgumentException("agentKey 必填");
    Objects.requireNonNull(userId, "userId");
    RuntimeConfig config = load(agentKey.trim());
    Instant createdAt = Instant.now();
    var conversation = traces.startNewConversation(userId, config.agentKey(), createdAt);
    return new CreatedConversation(conversation.conversationId(), conversation.startedAt());
  }

  /** Starts a run only inside the active conversation created for this authenticated caller. */
  public StreamingRun startStreaming(
      String agentKey, UUID userId, UUID conversationId, String message, Executor executor) {
    RuntimeConfig config = requireStreamingInput(agentKey, userId, message, executor);
    Objects.requireNonNull(conversationId, "conversationId");
    Instant startedAt = Instant.now();
    ConversationSummary conversation =
        traces
            .findActiveConversation(conversationId, userId, config.agentKey(), startedAt)
            .orElseThrow(() -> new IllegalArgumentException("会话不存在或已结束"));
    return startStreaming(config, userId, message.trim(), conversation, startedAt, executor);
  }

  private RuntimeConfig requireStreamingInput(
      String agentKey, UUID userId, String message, Executor executor) {
    if (agentKey == null || agentKey.isBlank()) throw new IllegalArgumentException("agentKey 必填");
    Objects.requireNonNull(userId, "userId");
    if (message == null || message.isBlank()) throw new IllegalArgumentException("message 不能为空");
    Objects.requireNonNull(executor, "executor");
    return load(agentKey.trim());
  }

  private StreamingRun startStreaming(
      RuntimeConfig config,
      UUID userId,
      String message,
      ConversationSummary conversation,
      Instant startedAt,
      Executor executor) {
    UUID runId = UUID.randomUUID();
    List<ConversationMessage> history =
        traces.recentConversationMessages(conversation.conversationId(), 20);
    traces.insertRun(
        runId,
        userId,
        conversation.conversationId(),
        config.agentKey(),
        config.version(),
        config.frameworkKey(),
        config.modelKey(),
        truncate(message, 1000));
    traces.appendConversationMessage(
        conversation.conversationId(), runId, "USER", message, startedAt);
    traces.appendStreamEvent(
        runId, "RUN_STATE", Map.of("status", "RUNNING", "summary", "已建立运行上下文"));
    executor.execute(
        () ->
            executeStreaming(
                config, userId, message, history, runId, conversation.conversationId(), startedAt));
    return new StreamingRun(
        runId,
        conversation.conversationId(),
        config.agentKey(),
        config.version(),
        "RUNNING",
        startedAt);
  }

  private void executeStreaming(
      RuntimeConfig config,
      UUID userId,
      String message,
      List<ConversationMessage> history,
      UUID runId,
      UUID conversationId,
      Instant startedAt) {
    try {
      var outcome =
          runAdapter(
              config, runId, userId, message, history, event -> appendStreamEvent(runId, event));
      if (outcome.pendingApproval() != null) {
        waitForApproval(runId, userId, outcome.pendingApproval());
        return;
      }
      String response = outcome.response();
      Instant completedAt = Instant.now();
      long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();
      traces.markCompleted(
          runId,
          "SUCCEEDED",
          completedAt,
          durationMs,
          outcome.toolCalls(),
          0,
          0,
          0,
          config.modelKey(),
          null,
          null,
          truncate(response, 1500));
      traces.appendConversationMessage(conversationId, runId, "ASSISTANT", response, completedAt);
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "SUCCEEDED"));
    } catch (RuntimeException exception) {
      failure(runId, conversationId, exception.getMessage());
      traces.appendStreamEvent(
          runId, "ERROR", Map.of("message", safeMessage(exception.getMessage())));
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "FAILED"));
    }
  }

  private RuntimeConfig load(String agentKey) {
    var rows =
        jdbc.query(
            PUBLISHED_AGENT_SQL,
            (rs, row) -> new PublishedConfig(rs.getInt("version"), rs.getString("configuration")),
            agentKey);
    if (rows.isEmpty()) throw new IllegalArgumentException("所选 Agent 尚未发布");
    try {
      var published = rows.get(0);
      JsonNode root = mapper.readTree(published.configuration());
      JsonNode runtime = object(root, "agentRuntime", "已发布版本缺少运行时快照，请重新发布");
      JsonNode provider = object(runtime, "provider", "已发布版本缺少 Provider 快照");
      JsonNode model = object(runtime, "model", "已发布版本缺少模型快照");
      JsonNode prompt = object(runtime, "prompt", "已发布版本缺少提示词快照，请重新发布");
      JsonNode credential = object(runtime, "credential", "已发布版本缺少 Provider 凭据快照，请重新发布");
      String providerKey = text(provider, "key", "Provider 快照不完整");
      String modelKey = text(model, "key", "模型快照不完整");
      String endpoint =
          text(object(provider, "config", "Provider 配置缺失"), "endpoint", "Provider 未配置 endpoint")
              .replaceAll("/+$", "");
      String modelName = optional(object(model, "config", "模型配置缺失"), "model", modelKey);
      String promptText = text(object(prompt, "config", "提示词配置缺失"), "template", "提示词模板为空");
      JsonNode memory = object(runtime, "memory", "已发布版本缺少 Memory 快照，请重新发布");
      return new RuntimeConfig(
          agentKey,
          published.version(),
          text(root, "frameworkKey", "已发布版本未绑定 Framework"),
          providerKey,
          modelKey,
          modelName,
          endpoint,
          promptText,
          root.path("temperature").asDouble(0.5),
          strings(root, "toolKeys"),
          skills(runtime),
          hooks(runtime),
          object(memory, "config", "Memory 配置缺失").path("maxTokens").asInt(12_000),
          root.path("maxToolCalls").asInt(8),
          credential.path("keyVersion").asInt(0),
          text(credential, "ciphertext", "凭据快照缺少密文"),
          text(credential, "iv", "凭据快照缺少初始化向量"));
    } catch (PlaygroundRuntimeUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new PlaygroundRuntimeUnavailableException("已发布 Agent 配置解析失败", exception);
    }
  }

  private char[] decrypt(RuntimeConfig config) {
    if (config.credentialVersion() < 1) {
      throw new PlaygroundRuntimeUnavailableException("已发布 Provider 凭据快照版本不合法");
    }
    byte[] ciphertext = Base64.getDecoder().decode(config.ciphertext());
    byte[] iv = Base64.getDecoder().decode(config.iv());
    try {
      var ref =
          new ComponentRef(
              new ComponentKey(config.providerKey()),
              new ComponentVersion(config.credentialVersion()));
      var cipher =
          AesGcmCredentialCipher.fromEnvironment(
              Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, masterKeyFile.toString()), ref);
      return cipher.decrypt(new EncryptedSecret(ref, ciphertext, iv));
    } finally {
      Arrays.fill(ciphertext, (byte) 0);
      Arrays.fill(iv, (byte) 0);
    }
  }

  private AdapterOutcome runAdapter(
      RuntimeConfig config,
      UUID runId,
      UUID userId,
      String message,
      List<ConversationMessage> history,
      java.util.function.Consumer<RunEvent> streamConsumer) {
    return runAdapter(
        config,
        runId,
        userId,
        message,
        config.toolKeys(),
        config.skills().stream().map(PublishedSkill::skill).toList(),
        history,
        "admin.playground",
        streamConsumer);
  }

  private AdapterOutcome runAdapter(
      RuntimeConfig config,
      UUID runId,
      UUID userId,
      String message,
      List<String> toolKeys,
      List<RunRequest.Skill> skills,
      List<ConversationMessage> history,
      String toolSource,
      java.util.function.Consumer<RunEvent> streamConsumer) {
    char[] secret = decrypt(config);
    try {
      var request =
          request(config, runId, userId, message, toolKeys, skills, history, toolSource, secret);
      var events =
          frameworks
              .required(config.frameworkKey())
              .run(request)
              .doOnNext(
                  event -> {
                    traces.appendEvent(runId, event);
                    streamConsumer.accept(event);
                  })
              .collectList()
              .block();
      if (events == null || events.isEmpty()) {
        throw new PlaygroundRuntimeUnavailableException("Agent 框架未返回任何运行事件");
      }
      var failed =
          events.stream().filter(event -> event.type() == RunEvent.Type.RUN_FAILED).findFirst();
      if (failed.isPresent()) {
        throw new PlaygroundRuntimeUnavailableException(failureMessage(failed.get()));
      }
      var pendingApproval = pendingApproval(request, events);
      String response = response(events);
      if (response.isBlank() && pendingApproval == null) {
        throw new PlaygroundRuntimeUnavailableException("AI 返回内容为空");
      }
      int toolCalls =
          (int) events.stream().filter(event -> event.type() == RunEvent.Type.TOOL_STARTED).count();
      return new AdapterOutcome(response, toolCalls, pendingApproval);
    } finally {
      Arrays.fill(secret, '\0');
    }
  }

  private RunRequest request(
      RuntimeConfig config,
      UUID runId,
      UUID userId,
      String message,
      List<String> toolKeys,
      List<RunRequest.Skill> skills,
      List<ConversationMessage> history,
      String toolSource,
      char[] secret) {
    var resolvedTools = resolvedTools(toolKeys);
    return new RunRequest(
        runId.toString(),
        userId.toString(),
        message,
        runtimeSystemPrompt(config.systemPrompt()),
        resolvedConfig(config),
        new RunRequest.ModelEndpoint(
            URI.create(config.endpoint()),
            config.modelName(),
            new RunRequest.ModelCredential(secret)),
        resolvedTools,
        skills,
        config.hooks().stream()
            .map(
                hook ->
                    new RunRequest.Hook(
                        hook.key(),
                        hook.phase(),
                        0,
                        hook.mandatory(),
                        HookDefinition.FailurePolicy.FAIL_CLOSED,
                        context -> hookExecutor.execute(hook.key(), context)))
            .toList(),
        new RunRequest.Memory(
            history.stream().map(item -> item.role() + ": " + item.content()).toList(),
            config.memoryMaxTokens()),
        new ToolExecutionContext(
            userId.toString(), runId.toString(), requiredScopes(resolvedTools), toolSource));
  }

  private static PublishedSkill requiredTaskSkill(RuntimeConfig config, String skillKey) {
    return config.skills().stream()
        .filter(skill -> skill.skill().key().equals(skillKey))
        .findFirst()
        .orElseThrow(
            () -> new PlaygroundRuntimeUnavailableException("已发布 Agent 未绑定指定 Skill：" + skillKey));
  }

  private void validateTaskTools(RuntimeConfig config, PublishedSkill skill) {
    Set<String> agentTools = Set.copyOf(config.toolKeys());
    if (!agentTools.containsAll(skill.requiredToolKeys())) {
      var missing = new java.util.LinkedHashSet<>(skill.requiredToolKeys());
      missing.removeAll(agentTools);
      throw new PlaygroundRuntimeUnavailableException(
          "指定 Skill 的必要 Tool 未绑定到已发布 Agent：" + String.join(",", missing));
    }
    for (var tool : resolvedTools(skill.requiredToolKeys().stream().sorted().toList())) {
      if (tool.descriptor().sideEffect()
          != happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect.NONE) {
        throw new PlaygroundRuntimeUnavailableException(
            "后台任务只允许只读 Tool：" + tool.descriptor().toolKey());
      }
    }
  }

  private static Set<String> requiredScopes(
      List<happy.jayden.yang.agentbuilder.core.tool.ResolvedTool> resolvedTools) {
    var scopes = new java.util.LinkedHashSet<String>();
    for (var tool : resolvedTools) {
      scopes.addAll(tool.descriptor().requiredScopes());
    }
    return Set.copyOf(scopes);
  }

  private Set<String> requiredScopes(String toolKey) {
    return tools.descriptors().stream()
        .filter(descriptor -> descriptor.toolKey().equals(toolKey))
        .findFirst()
        .map(descriptor -> Set.copyOf(descriptor.requiredScopes()))
        .orElseThrow(() -> new IllegalArgumentException("未知 Tool：" + toolKey));
  }

  private List<happy.jayden.yang.agentbuilder.core.tool.ResolvedTool> resolvedTools(
      List<String> toolKeys) {
    var descriptors =
        tools.descriptors().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor::toolKey,
                    java.util.function.Function.identity()));
    var bindings =
        toolKeys.stream()
            .map(
                key -> {
                  var descriptor = descriptors.get(key);
                  if (descriptor == null) {
                    throw new PlaygroundRuntimeUnavailableException(
                        "已发布 Agent 绑定的 Tool 不可用：" + key);
                  }
                  return new ToolBinding(
                      new ComponentKey(key),
                      new ComponentVersion(descriptor.contractVersion()),
                      true);
                })
            .toList();
    return tools.resolve(bindings).tools();
  }

  private static ResolvedAgentConfig resolvedConfig(RuntimeConfig config) {
    var source = EffectiveValueSource.agentOverride();
    var sources =
        new PublishedResolvedConfigSources(
            source, source, source, source, source, source, source, source, source, source, source,
            source, source);
    return new ResolvedAgentConfig(
        "personal-fitness",
        new RuntimeLimits(90, config.maxToolCalls(), 12_000, 4_096, BigDecimal.ONE, 1),
        new ModelParameters(BigDecimal.valueOf(config.temperature()), new BigDecimal("0.9"), 4_096),
        RetryPolicy.NONE,
        sources);
  }

  private static String runtimeSystemPrompt(String configuredPrompt) {
    return configuredPrompt
        + "\n\n<runtime_context>"
        + "当前日期（Asia/Shanghai）："
        + LocalDate.now(USER_ZONE)
        + "。处理今天、明天、本周等相对日期时，必须以此日期为准。"
        + "</runtime_context>"
        + "\n\n<runtime_confirmation_protocol>"
        + "对于说明由运行时确认的写入型 Tool，用户说“确认后保存”“确认后执行”或同义表达，"
        + "表示本次请求已经同意发起 Tool 调用，而不是要求文字二次确认。"
        + "生成候选后必须在同一次运行中调用该 Tool，并原样传入候选参数。"
        + "运行时会冻结参数并展示确认卡，只有用户点击确认卡才产生副作用；"
        + "不得要求用户通过文字再次确认，也不得在确认卡前执行副作用。"
        + "</runtime_confirmation_protocol>";
  }

  private static String response(List<RunEvent> events) {
    var typed =
        events.stream()
            .filter(
                event ->
                    event.payload() instanceof RunEvent.ReplyStarted
                        || event.payload() instanceof RunEvent.BlockStarted
                        || event.payload() instanceof RunEvent.BlockDelta
                        || event.payload() instanceof RunEvent.BlockCompleted
                        || event.payload() instanceof RunEvent.ReplyEnded)
            .toList();
    if (!typed.isEmpty()) {
      try {
        var reply = AssistantReply.rebuild(typed);
        return reply.blocks().stream()
            .filter(
                happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock.Text.class::isInstance)
            .map(happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock.Text.class::cast)
            .map(happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock.Text::text)
            .collect(java.util.stream.Collectors.joining())
            .trim();
      } catch (IllegalArgumentException ignored) {
        // Some native frameworks emit tool lifecycle events with a distinct reply ID.
      }
    }
    return events.stream()
        .filter(event -> event.type() == RunEvent.Type.MODEL_DELTA)
        .map(event -> String.valueOf(event.data().getOrDefault("text", "")))
        .collect(java.util.stream.Collectors.joining())
        .trim();
  }

  private static String failureMessage(RunEvent event) {
    Object raw = event.data().get("result");
    if (raw instanceof happy.jayden.yang.agentbuilder.core.runtime.RunResult result
        && result.failure().isPresent()) {
      return result.failure().get().message();
    }
    return "Agent 框架执行失败";
  }

  private void appendStreamEvent(UUID runId, RunEvent event) {
    var payload = new java.util.LinkedHashMap<String, Object>(event.data());
    payload.put("eventType", event.type().name());
    if (event.type() == RunEvent.Type.MODEL_DELTA) {
      traces.appendStreamEvent(runId, "TEXT_DELTA", payload);
    } else {
      traces.appendStreamEvent(runId, "RUN_EVENT", payload);
    }
  }

  /** Applies one durable user decision to the exact Tool arguments frozen when the run paused. */
  public ApprovalDecision decide(
      UUID runId, UUID userId, UUID approvalId, String decision, String idempotencyKey) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(approvalId, "approvalId");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("Idempotency-Key 必填");
    }
    String normalized = decision == null ? "" : decision.trim().toUpperCase(java.util.Locale.ROOT);
    var decisionResult =
        traces.decideApproval(runId, approvalId, userId, normalized, idempotencyKey);
    var approval = decisionResult.approval();
    if (!decisionResult.newlyDecided()) {
      var trace =
          traces.findTrace(runId).orElseThrow(() -> new IllegalArgumentException("运行记录不存在"));
      return new ApprovalDecision(
          runId,
          traces
              .findRunConversation(runId)
              .orElseThrow(() -> new IllegalArgumentException("会话记录不存在")),
          trace.status(),
          trace.completedAt() == null ? Instant.now() : trace.completedAt(),
          null);
    }
    if ("REJECT".equals(normalized)) {
      traces.appendLifecycleEvent(
          runId,
          RunEvent.Type.CONFIRMATION_RECEIVED.name(),
          Map.of("approvalId", approvalId.toString(), "decision", "REJECT"));
      traces.appendStreamEvent(
          runId, "APPROVAL", Map.of("approvalId", approvalId.toString(), "status", "REJECTED"));
      return completeApprovalRun(runId, "CANCELLED", "好的，这次不会保存。", null);
    }
    if (!"APPROVE".equals(normalized)) {
      throw new IllegalArgumentException("decision 必须是 APPROVE 或 REJECT");
    }
    try {
      traces.appendLifecycleEvent(
          runId,
          RunEvent.Type.CONFIRMATION_RECEIVED.name(),
          Map.of("approvalId", approvalId.toString(), "decision", "APPROVE"));
      Map<String, Object> arguments = approvedArguments(approval);
      traces.appendLifecycleEvent(
          runId,
          RunEvent.Type.TOOL_STARTED.name(),
          Map.of(
              "toolCallId",
              approval.toolCallId().toString(),
              "toolName",
              approval.toolKey(),
              "arguments",
              arguments));
      Object result =
          tools.invoke(
              approval.toolKey(),
              arguments,
              new ToolExecutionContext(
                  userId.toString(),
                  runId.toString(),
                  requiredScopes(approval.toolKey()),
                  "approved-tool:" + approvalId));
      traces.appendLifecycleEvent(
          runId,
          RunEvent.Type.TOOL_RESULT.name(),
          Map.of(
              "toolCallId",
              approval.toolCallId().toString(),
              "toolName",
              approval.toolKey(),
              "result",
              result));
      traces.appendStreamEvent(
          runId, "APPROVAL", Map.of("approvalId", approvalId.toString(), "status", "APPROVED"));
      return completeApprovalRun(runId, "SUCCEEDED", "已按你确认的内容保存。", result);
    } catch (Exception exception) {
      traces.appendLifecycleEvent(
          runId,
          RunEvent.Type.TOOL_FAILED.name(),
          Map.of(
              "toolCallId",
              approval.toolCallId().toString(),
              "toolName",
              approval.toolKey(),
              "errorMessage",
              safeMessage(exception)));
      completeApprovalRun(runId, "FAILED", "保存失败，请稍后重试。", null);
      throw new PlaygroundRuntimeUnavailableException("确认后的 Tool 执行失败", exception);
    }
  }

  private void waitForApproval(UUID runId, UUID userId, PendingApproval pending) {
    var approval =
        traces.requestApproval(
            runId, userId, pending.toolKey(), pending.title(), pending.arguments());
    traces.appendStreamEvent(
        runId, "RUN_STATE", Map.of("status", "WAITING_APPROVAL", "summary", "已准备好操作，等待你的确认"));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("approvalId", approval.approvalId().toString());
    payload.put("toolCallId", approval.toolCallId().toString());
    payload.put("status", "REQUESTED");
    payload.put("title", approval.title());
    if (!pending.proposal().isEmpty()) {
      payload.put("proposal", pending.proposal());
    }
    traces.appendStreamEvent(runId, "APPROVAL", payload);
  }

  private ApprovalDecision completeApprovalRun(
      UUID runId, String status, String appendedText, Object toolResult) {
    var trace = traces.findTrace(runId).orElseThrow(() -> new IllegalArgumentException("运行记录不存在"));
    Instant completedAt = Instant.now();
    long durationMs = Math.max(0, completedAt.toEpochMilli() - trace.startedAt().toEpochMilli());
    String output =
        (trace.outputSummary() == null ? "" : trace.outputSummary())
            + (appendedText == null || appendedText.isBlank() ? "" : "\n\n" + appendedText);
    int completedTools = "SUCCEEDED".equals(status) ? trace.toolCalls() + 1 : trace.toolCalls();
    traces.markCompleted(
        runId,
        status,
        completedAt,
        durationMs,
        completedTools,
        trace.promptTokens(),
        trace.completionTokens(),
        trace.costUsd(),
        trace.modelKey(),
        "FAILED".equals(status) ? "TOOL_EXECUTION_FAILED" : null,
        "FAILED".equals(status) ? appendedText : null,
        truncate(output, 1500));
    if (appendedText != null && !appendedText.isBlank()) {
      traces.appendStreamEvent(
          runId,
          "TEXT_DELTA",
          Map.of("messageId", runId.toString(), "delta", "\n\n" + appendedText));
    }
    traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", status));
    return new ApprovalDecision(
        runId,
        traces
            .findRunConversation(runId)
            .orElseThrow(() -> new IllegalArgumentException("会话记录不存在")),
        status,
        completedAt,
        toolResult);
  }

  private PendingApproval pendingApproval(RunRequest request, List<RunEvent> events) {
    var confirmation =
        events.stream()
            .filter(event -> event.type() == RunEvent.Type.CONFIRMATION_REQUIRED)
            .findFirst();
    if (confirmation.isEmpty()) return null;
    RunEvent confirmationEvent = confirmation.get();
    Object rawCalls = confirmationEvent.data().get("toolCalls");
    Map<?, ?> raw;
    if (rawCalls instanceof List<?> calls
        && calls.size() == 1
        && calls.get(0) instanceof Map<?, ?> call) {
      raw = call;
    } else if (confirmationEvent.data().containsKey("toolName")) {
      raw = confirmationEvent.data();
    } else {
      throw new PlaygroundRuntimeUnavailableException("框架确认事件必须包含一个 Tool 调用");
    }
    String runtimeName = requiredText(raw.get("toolName"), "确认事件缺少 toolName");
    String toolKey =
        request.tools().stream()
            .filter(tool -> tool.descriptor().runtimeName().equals(runtimeName))
            .map(tool -> tool.descriptor().toolKey())
            .findFirst()
            .orElseThrow(
                () -> new PlaygroundRuntimeUnavailableException("确认事件引用了未绑定 Tool: " + runtimeName));
    Object rawArguments = raw.get("arguments");
    if (!(rawArguments instanceof Map<?, ?> arguments)) {
      throw new PlaygroundRuntimeUnavailableException("确认事件缺少 Tool 参数");
    }
    var frozenArguments = stringKeyedMap(arguments);
    if (frozenArguments.isEmpty() && raw.get("toolCallId") instanceof String toolCallId) {
      frozenArguments = argumentsFromToolCallBlocks(events, toolCallId);
    }
    Map<String, Object> proposal =
        "fitness.plan.save".equals(toolKey)
            ? planProposal(
                    frozenArguments,
                    trustedExerciseNames(request, events, confirmationEvent.sequence()))
                .orElse(Map.of())
            : Map.of();
    return new PendingApproval(toolKey, titleFor(toolKey), frozenArguments, proposal);
  }

  private Map<String, String> trustedExerciseNames(
      RunRequest request, List<RunEvent> events, long confirmationSequence) {
    Set<String> trustedRuntimeNames =
        request.tools().stream()
            .filter(tool -> TRUSTED_EXERCISE_NAME_TOOL_KEYS.contains(tool.descriptor().toolKey()))
            .map(tool -> tool.descriptor().runtimeName())
            .collect(Collectors.toUnmodifiableSet());
    var names = new LinkedHashMap<String, String>();
    events.stream()
        .filter(event -> event.sequence() < confirmationSequence)
        .filter(event -> event.type() == RunEvent.Type.TOOL_RESULT)
        .filter(event -> trustedRuntimeNames.contains(event.data().get("toolName")))
        .map(event -> event.data().get("result"))
        .filter(Objects::nonNull)
        .forEach(result -> collectExerciseNames(mapper.valueToTree(result), names));
    return Map.copyOf(names);
  }

  private void collectExerciseNames(JsonNode node, Map<String, String> names) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isTextual()) {
      String encoded = node.textValue().trim();
      if (!(encoded.startsWith("{") || encoded.startsWith("["))) {
        return;
      }
      try {
        collectExerciseNames(mapper.readTree(encoded), names);
      } catch (JsonProcessingException ignored) {
        // A non-JSON encoded result cannot contribute trusted exercise facts.
      }
      return;
    }
    if (node.isObject()) {
      JsonNode exerciseId = node.get("exerciseId");
      JsonNode name = node.get("name");
      if (exerciseId != null && exerciseId.isTextual() && name != null && name.isTextual()) {
        try {
          String canonicalId = UUID.fromString(exerciseId.textValue().trim()).toString();
          String normalizedName = name.textValue().trim();
          if (!normalizedName.isEmpty()) {
            names.putIfAbsent(canonicalId, normalizedName);
          }
        } catch (IllegalArgumentException ignored) {
          // Invalid IDs are not trusted action-library facts.
        }
      }
      node.elements().forEachRemaining(child -> collectExerciseNames(child, names));
      return;
    }
    if (node.isArray()) {
      node.elements().forEachRemaining(child -> collectExerciseNames(child, names));
    }
  }

  private Map<String, Object> argumentsFromToolCallBlocks(
      List<RunEvent> events, String toolCallId) {
    var rawArguments = new StringBuilder();
    String blockId = "tool-call-" + toolCallId;
    for (var event : events) {
      if (event.type() != RunEvent.Type.BLOCK_DELTA
          || !blockId.equals(event.data().get("blockId"))) {
        continue;
      }
      Object delta = event.data().get("delta");
      if (delta instanceof String value) {
        rawArguments.append(value);
      }
    }
    if (rawArguments.isEmpty()) {
      return Map.of();
    }
    try {
      JsonNode parsed = parseToolArguments(rawArguments.toString());
      if (!parsed.isObject()) {
        throw new PlaygroundRuntimeUnavailableException("确认 Tool 参数不是 JSON 对象");
      }
      return mapper.convertValue(parsed, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (JsonProcessingException exception) {
      throw new PlaygroundRuntimeUnavailableException("无法还原确认 Tool 参数", exception);
    }
  }

  private JsonNode parseToolArguments(String rawArguments) throws JsonProcessingException {
    try {
      return mapper.readTree(rawArguments);
    } catch (JsonProcessingException original) {
      String completed = completeJsonContainers(rawArguments);
      if (completed.equals(rawArguments)) {
        throw original;
      }
      return mapper.readTree(completed);
    }
  }

  private static String completeJsonContainers(String value) {
    var containers = new ArrayDeque<Character>();
    boolean inString = false;
    boolean escaped = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (current == '"') {
          inString = false;
        }
        continue;
      }
      if (current == '"') {
        inString = true;
      } else if (current == '{' || current == '[') {
        containers.push(current);
      } else if (current == '}' || current == ']') {
        if (containers.isEmpty()
            || (current == '}' && containers.peek() != '{')
            || (current == ']' && containers.peek() != '[')) {
          return value;
        }
        containers.pop();
      }
    }
    if (inString || containers.isEmpty()) {
      return value;
    }
    var completed = new StringBuilder(value);
    while (!containers.isEmpty()) {
      completed.append(containers.pop() == '{' ? '}' : ']');
    }
    return completed.toString();
  }

  private String titleFor(String toolKey) {
    return tools.descriptors().stream()
        .filter(descriptor -> descriptor.toolKey().equals(toolKey))
        .map(happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor::displayName)
        .findFirst()
        .orElse(toolKey);
  }

  private static Map<String, Object> approvedArguments(
      JdbcRunTraceRepository.ApprovalRecord approval) {
    var arguments = new LinkedHashMap<>(approval.arguments());
    if (!"fitness.plan.save".equals(approval.toolKey())) {
      return Map.copyOf(arguments);
    }
    Object request = arguments.get("request");
    if (!(request instanceof Map<?, ?> requestMap)) {
      return Map.copyOf(arguments);
    }
    var frozenRequest = new LinkedHashMap<>(stringKeyedMap(requestMap));
    frozenRequest.put("approvalId", approval.approvalId().toString());
    arguments.put("request", Map.copyOf(frozenRequest));
    return Map.copyOf(arguments);
  }

  private static java.util.Optional<Map<String, Object>> planProposal(
      Map<String, Object> arguments, Map<String, String> exerciseNames) {
    Object request = arguments.get("request");
    if (!(request instanceof Map<?, ?> value)) {
      return java.util.Optional.empty();
    }
    Map<String, Object> plan = stringKeyedMap(value);
    if (!(plan.get("scope") instanceof String) || !(plan.get("days") instanceof List<?> rawDays)) {
      return java.util.Optional.empty();
    }
    var days = new ArrayList<Map<String, Object>>();
    for (Object rawDay : rawDays) {
      if (!(rawDay instanceof Map<?, ?> dayValue)) {
        return java.util.Optional.empty();
      }
      var day = new LinkedHashMap<>(stringKeyedMap(dayValue));
      Object rawExerciseIds = day.remove("exerciseIds");
      if (!(rawExerciseIds instanceof List<?> exerciseIds)) {
        return java.util.Optional.empty();
      }
      var exercises = new ArrayList<Map<String, Object>>();
      for (int index = 0; index < exerciseIds.size(); index++) {
        Object rawExerciseId = exerciseIds.get(index);
        String exerciseId =
            rawExerciseId instanceof UUID valueId
                ? valueId.toString()
                : rawExerciseId instanceof String text ? text.trim() : "";
        if (exerciseId.isEmpty()) {
          return java.util.Optional.empty();
        }
        String canonicalId;
        try {
          canonicalId = UUID.fromString(exerciseId).toString();
        } catch (IllegalArgumentException ignored) {
          canonicalId = exerciseId;
        }
        exercises.add(
            Map.of(
                "exerciseId",
                exerciseId,
                "name",
                exerciseNames.getOrDefault(canonicalId, "动作 " + (index + 1))));
      }
      day.put("exercises", List.copyOf(exercises));
      days.add(Map.copyOf(day));
    }
    var proposal = new LinkedHashMap<String, Object>();
    proposal.put("scope", plan.get("scope"));
    proposal.put("days", List.copyOf(days));
    return java.util.Optional.of(Map.copyOf(proposal));
  }

  private static Map<String, Object> stringKeyedMap(Map<?, ?> values) {
    var result = new LinkedHashMap<String, Object>();
    values.forEach(
        (key, value) -> {
          if (!(key instanceof String text) || text.isBlank()) {
            throw new PlaygroundRuntimeUnavailableException("Tool 参数键必须是非空字符串");
          }
          result.put(text, value);
        });
    return Map.copyOf(result);
  }

  private static String requiredText(Object value, String failure) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new PlaygroundRuntimeUnavailableException(failure);
    }
    return text;
  }

  private void failure(UUID runId, UUID conversationId, String error) {
    Instant completedAt = Instant.now();
    traces.markCompleted(
        runId, "FAILED", completedAt, 0, 0, 0, 0, 0, null, "RUNTIME_ERROR", safeMessage(error), "");
    traces.appendConversationMessage(
        conversationId, runId, "ASSISTANT", "本次请求暂未完成，请稍后重试。", completedAt);
  }

  private static void appendHistory(
      List<Map<String, Object>> messages, List<ConversationMessage> history) {
    for (var item : history) {
      if ("USER".equals(item.role()))
        messages.add(Map.of("role", "user", "content", item.content()));
      if ("ASSISTANT".equals(item.role()))
        messages.add(Map.of("role", "assistant", "content", item.content()));
    }
  }

  private static JsonNode object(JsonNode source, String key, String failure) {
    JsonNode value = source.path(key);
    if (!value.isObject()) throw new PlaygroundRuntimeUnavailableException(failure);
    return value;
  }

  private static List<String> strings(JsonNode source, String key) {
    JsonNode value = source.path(key);
    if (!value.isArray()) return List.of();
    var values = new ArrayList<String>();
    for (JsonNode item : value) {
      String text = item.asText("").trim();
      if (!text.isBlank()) values.add(text);
    }
    return List.copyOf(values);
  }

  private static List<PublishedSkill> skills(JsonNode runtime) {
    JsonNode values = runtime.path("skills");
    if (!values.isArray()) {
      throw new PlaygroundRuntimeUnavailableException("已发布版本缺少 Skill 快照，请重新发布");
    }
    var skills = new ArrayList<PublishedSkill>();
    for (JsonNode item : values) {
      JsonNode config = object(item, "config", "Skill 快照配置缺失");
      String description = text(config, "description", "Skill 描述不能为空");
      String whenToUse = optional(config, "whenToUse", "");
      String content = text(config, "content", "Skill 内容不能为空");
      skills.add(
          new PublishedSkill(
              new RunRequest.Skill(
                  text(item, "key", "Skill 快照不完整"),
                  whenToUse.isBlank() ? description : description + "\n适用场景：" + whenToUse,
                  content,
                  Map.of("SKILL.md", content),
                  Set.of("SKILL.md"),
                  Set.of()),
              Set.copyOf(strings(config, "requiredToolKeys")),
              item.path("version").asInt(0)));
    }
    return List.copyOf(skills);
  }

  private static List<HookConfig> hooks(JsonNode runtime) {
    JsonNode values = runtime.path("hooks");
    if (!values.isArray()) {
      throw new PlaygroundRuntimeUnavailableException("已发布版本缺少 Hook 快照，请重新发布");
    }
    var hooks = new ArrayList<HookConfig>();
    for (JsonNode item : values) {
      JsonNode config = object(item, "config", "Hook 快照配置缺失");
      hooks.add(
          new HookConfig(
              text(item, "key", "Hook 快照不完整"),
              hookPhase(text(config, "phase", "Hook phase 不能为空")),
              config.path("mandatory").asBoolean(false)));
    }
    return List.copyOf(hooks);
  }

  private static HookDefinition.Phase hookPhase(String value) {
    return switch (value) {
      case "BEFORE_MODEL", "PRE_MODEL" -> HookDefinition.Phase.PRE_MODEL;
      case "BEFORE_AGENT", "PRE_AGENT" -> HookDefinition.Phase.PRE_AGENT;
      case "PRE_TOOL" -> HookDefinition.Phase.PRE_TOOL;
      case "POST_TOOL" -> HookDefinition.Phase.POST_TOOL;
      case "POST_MODEL" -> HookDefinition.Phase.POST_MODEL;
      case "POST_AGENT" -> HookDefinition.Phase.POST_AGENT;
      default -> throw new PlaygroundRuntimeUnavailableException("不支持的 Hook phase: " + value);
    };
  }

  private static String text(JsonNode source, String key, String failure) {
    String value = source.path(key).asText("").trim();
    if (value.isBlank()) throw new PlaygroundRuntimeUnavailableException(failure);
    return value;
  }

  private static String optional(JsonNode source, String key, String fallback) {
    String value = source.path(key).asText("").trim();
    return value.isBlank() ? fallback : value;
  }

  private static String truncate(String value, int limit) {
    if (value == null) return "";
    return value.length() <= limit ? value : value.substring(0, limit) + "…";
  }

  private static String safeMessage(Exception exception) {
    return safeMessage(exception.getMessage());
  }

  private static String safeMessage(String message) {
    return message == null || message.isBlank() ? "未知运行时错误" : truncate(message, 300);
  }

  private record PublishedConfig(int version, String configuration) {}

  private record RuntimeConfig(
      String agentKey,
      int version,
      String frameworkKey,
      String providerKey,
      String modelKey,
      String modelName,
      String endpoint,
      String systemPrompt,
      double temperature,
      List<String> toolKeys,
      List<PublishedSkill> skills,
      List<HookConfig> hooks,
      int memoryMaxTokens,
      int maxToolCalls,
      int credentialVersion,
      String ciphertext,
      String iv) {}

  private record HookConfig(String key, HookDefinition.Phase phase, boolean mandatory) {}

  private record PublishedSkill(
      RunRequest.Skill skill, Set<String> requiredToolKeys, int revision) {
    private PublishedSkill {
      Objects.requireNonNull(skill, "skill");
      requiredToolKeys = Set.copyOf(Objects.requireNonNull(requiredToolKeys, "requiredToolKeys"));
      if (revision < 1) {
        throw new PlaygroundRuntimeUnavailableException("Skill 快照 revision 不合法");
      }
    }
  }

  private record AdapterOutcome(String response, int toolCalls, PendingApproval pendingApproval) {}

  private record PendingApproval(
      String toolKey, String title, Map<String, Object> arguments, Map<String, Object> proposal) {}

  public record ApprovalDecision(
      UUID runId, UUID conversationId, String status, Instant updatedAt, Object toolResult) {}

  public record StreamingRun(
      UUID runId,
      UUID conversationId,
      String agentKey,
      int agentVersion,
      String status,
      Instant createdAt) {}

  public record CreatedConversation(UUID conversationId, Instant createdAt) {}

  public record TaskRunResult(
      UUID runId,
      String agentKey,
      int agentVersion,
      String skillKey,
      int skillRevision,
      String output) {}

  public static final class TaskConfigurationException extends RuntimeException {
    TaskConfigurationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class TaskExecutionException extends RuntimeException {
    TaskExecutionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class PlaygroundRuntimeUnavailableException extends RuntimeException {
    public PlaygroundRuntimeUnavailableException(String message) {
      super(message);
    }

    public PlaygroundRuntimeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
