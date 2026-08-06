package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.infrastructure.security.AesGcmCredentialCipher;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcAdminWorkbenchStore implements AdminWorkbenchPort {
  private static final String MASK = "••••••••";
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final Path masterKeyFile;

  public JdbcAdminWorkbenchStore(DataSource dataSource, ObjectMapper mapper, Path masterKeyFile) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.mapper = mapper.copy().findAndRegisterModules();
    this.masterKeyFile = masterKeyFile.toAbsolutePath().normalize();
  }

  @Override
  public WorkbenchSnapshot snapshot() {
    var agents = jdbc.query("SELECT * FROM agent_drafts ORDER BY agent_key", draftMapper());
    var components =
        jdbc.query(
            "SELECT component_type,component_key,display_name,description,version,status,tags,config::text FROM agent_component_projection ORDER BY component_type,component_key,version DESC",
            (rs, row) ->
                new ComponentView(
                    rs.getString("component_type"),
                    rs.getString("component_key"),
                    rs.getString("display_name"),
                    rs.getString("description"),
                    rs.getInt("version"),
                    rs.getString("status"),
                    List.of((String[]) rs.getArray("tags").getArray()),
                    read(rs.getString("config"), OBJECT_MAP)));
    var providers = providers();
    var runs = recentRuns();
    var configuredProviders = (int) providers.stream().filter(ProviderView::configured).count();
    var available =
        (int) components.stream().filter(item -> "AVAILABLE".equals(item.status())).count();
    var platformStatus = configuredProviders > 0 ? "READY" : "DEGRADED";
    return new WorkbenchSnapshot(
        new OverviewView(
            agents.size(), platformStatus, available, configuredProviders, runs.size()),
        agents,
        components,
        providers,
        runs);
  }

  @Override
  public Optional<AgentDraftView> findDraft(String agentKey) {
    return jdbc
        .query("SELECT * FROM agent_drafts WHERE agent_key=?", draftMapper(), agentKey)
        .stream()
        .findFirst();
  }

  @Override
  public AgentDraftView updateDraft(String agentKey, DraftUpdate update, long expectedRevision) {
    var changed =
        jdbc.update(
            "UPDATE agent_drafts SET name=?,description=?,status='DRAFT',framework_key=?,provider_key=?,model_key=?,prompt_key=?,tool_keys=?::jsonb,skill_keys=?::jsonb,hook_keys=?::jsonb,memory_key=?,temperature=?,max_tool_calls=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE agent_key=? AND revision=?",
            update.name(),
            update.description(),
            update.frameworkKey(),
            update.providerKey(),
            update.modelKey(),
            update.promptKey(),
            write(update.toolKeys()),
            write(update.skillKeys()),
            write(update.hookKeys()),
            update.memoryKey(),
            update.temperature(),
            update.maxToolCalls(),
            agentKey,
            expectedRevision);
    if (changed == 0) {
      if (findDraft(agentKey).isEmpty()) throw new NotFound("Agent 草稿不存在");
      throw new Conflict("Agent 草稿已经被其他操作更新，请刷新后重试");
    }
    return findDraft(agentKey).orElseThrow(() -> new NotFound("Agent 草稿不存在"));
  }

  @Override
  public ProviderView saveCredential(String providerKey, char[] credential) {
    var provider =
        providers().stream()
            .filter(item -> item.providerKey().equals(providerKey))
            .findFirst()
            .orElseThrow(() -> new NotFound("Provider 不存在"));
    var component = new ComponentRef(new ComponentKey(providerKey), new ComponentVersion(1));
    var cipher =
        AesGcmCredentialCipher.fromEnvironment(
            Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, masterKeyFile.toString()), component);
    var encrypted = cipher.encrypt(credential);
    var aad = (providerKey + "\u0000" + 1).getBytes(StandardCharsets.UTF_8);
    jdbc.update(
        "INSERT INTO agent_provider_credentials(provider_key,credential_ciphertext,credential_iv,credential_aad,credential_key_version,updated_at) VALUES (?,?,?,?,1,CURRENT_TIMESTAMP) ON CONFLICT(provider_key) DO UPDATE SET credential_ciphertext=EXCLUDED.credential_ciphertext,credential_iv=EXCLUDED.credential_iv,credential_aad=EXCLUDED.credential_aad,credential_key_version=EXCLUDED.credential_key_version,updated_at=CURRENT_TIMESTAMP",
        providerKey,
        encrypted.ciphertext(),
        encrypted.iv(),
        aad);
    return new ProviderView(
        provider.providerKey(), provider.displayName(), provider.endpoint(), true, MASK, "READY");
  }

  @Override
  public PublicationView publish(AgentDraftView draft) {
    return transactions.execute(
        ignored -> {
          var nextVersion =
              jdbc.queryForObject(
                  "SELECT COALESCE(MAX(version),0)+1 FROM agent_versions WHERE agent_key=?",
                  Integer.class,
                  draft.agentKey());
          var publishedAt = Instant.now();
          jdbc.update(
              "INSERT INTO agent_versions(agent_version_id,agent_key,version,status,configuration,published_at) VALUES (?,?,?,'PUBLISHED',?::jsonb,?)",
              UUID.randomUUID(),
              draft.agentKey(),
              nextVersion,
              write(draft),
              java.sql.Timestamp.from(publishedAt));
          var changed =
              jdbc.update(
                  "UPDATE agent_drafts SET status='PUBLISHED',current_published_version=?,revision=revision+1,updated_at=? WHERE agent_key=? AND revision=?",
                  nextVersion,
                  java.sql.Timestamp.from(publishedAt),
                  draft.agentKey(),
                  draft.revision());
          if (changed == 0) throw new Conflict("Agent 草稿在发布前已发生变化");
          return new PublicationView(draft.agentKey(), nextVersion, publishedAt);
        });
  }

  @Override
  public Optional<RunView> run(UUID runId) {
    return jdbc
        .query(
            "SELECT * FROM agent_runs WHERE run_id=?",
            (rs, row) -> runView(rs.getObject("run_id", UUID.class), true),
            runId)
        .stream()
        .findFirst();
  }

  void seedDefaults() {
    jdbc.update(
        "INSERT INTO agent_drafts(agent_key,name,description,status,framework_key,provider_key,model_key,prompt_key,tool_keys,skill_keys,hook_keys,memory_key,temperature,max_tool_calls) VALUES ('fitness.coach','瘦瘦健身教练','结合用户的训练、饮食与身体记录，提供可执行的日常陪伴。','DRAFT','agentscope','bailian','qwen-plus','fitness.coach.prompt','[]'::jsonb,'[]'::jsonb,'[]'::jsonb,'fitness.daily-memory',0.5,8) ON CONFLICT(agent_key) DO NOTHING");
    seedComponent(
        "FRAMEWORK",
        "agentscope",
        "AgentScope",
        "AgentScope Java 运行时适配器",
        "AVAILABLE",
        Map.of("tools", true, "skills", true, "hooks", true));
    seedComponent(
        "FRAMEWORK",
        "spring-ai-alibaba",
        "Spring AI Alibaba",
        "Spring AI Alibaba 运行时适配器",
        "AVAILABLE",
        Map.of("tools", true, "skills", false, "hooks", true));
    seedComponent(
        "PROVIDER",
        "bailian",
        "阿里云百炼",
        "兼容 OpenAI 协议的百炼模型服务",
        "AVAILABLE",
        Map.of("endpoint", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
    seedComponent(
        "MODEL",
        "qwen-plus",
        "通义千问 Plus",
        "日常对话与工具调用主模型",
        "AVAILABLE",
        Map.of("providerKey", "bailian", "toolCalling", true, "streaming", true, "vision", false));
    seedComponent(
        "MODEL",
        "qwen-vl-plus",
        "通义千问 VL Plus",
        "饮食图片识别模型",
        "AVAILABLE",
        Map.of("providerKey", "bailian", "toolCalling", false, "streaming", true, "vision", true));
    seedComponent(
        "PROMPT",
        "fitness.coach.prompt",
        "瘦瘦系统提示词",
        "健身陪伴场景的角色、边界与输出约束",
        "AVAILABLE",
        Map.of("format", "MUSTACHE", "variables", List.of("user_context", "current_goal")));
    seedComponent(
        "MEMORY",
        "fitness.daily-memory",
        "当日会话记忆",
        "24 小时会话窗口与摘要压缩策略",
        "AVAILABLE",
        Map.of("retentionHours", 24, "maxTokens", 12000));
    seedComponent(
        "TOOL",
        "fitness.profile.query",
        "读取用户档案",
        "读取基础信息、目标与偏好",
        "UNAVAILABLE",
        Map.of("risk", "LOW", "sideEffect", "READ_ONLY", "reason", "等待 Fitness Tool Bean 接线"));
    seedComponent(
        "TOOL",
        "fitness.workout.query",
        "读取训练记录",
        "按日期读取训练计划和完成记录",
        "UNAVAILABLE",
        Map.of("risk", "LOW", "sideEffect", "READ_ONLY", "reason", "等待 Fitness Tool Bean 接线"));
    seedComponent(
        "TOOL",
        "fitness.meal.query",
        "读取饮食记录",
        "读取历史饮食和当日推荐",
        "UNAVAILABLE",
        Map.of("risk", "LOW", "sideEffect", "READ_ONLY", "reason", "等待 Fitness Tool Bean 接线"));
    seedComponent(
        "TOOL",
        "fitness.plan.generate",
        "写入训练计划",
        "生成并保存某日训练计划",
        "UNAVAILABLE",
        Map.of("risk", "MEDIUM", "sideEffect", "WRITE", "reason", "等待 Fitness Tool Bean 接线"));
    seedComponent(
        "SKILL",
        "fitness.plan.skill",
        "训练计划编排",
        "根据目标和历史负荷制定训练计划",
        "DRAFT",
        Map.of(
            "requiredTools",
            List.of("fitness.profile.query", "fitness.workout.query", "fitness.plan.generate")));
    seedComponent(
        "SKILL",
        "fitness.meal.skill",
        "每日饮食建议",
        "结合训练与饮食记录推荐三餐",
        "DRAFT",
        Map.of("requiredTools", List.of("fitness.profile.query", "fitness.meal.query")));
    seedComponent(
        "HOOK",
        "fitness.safety",
        "健身安全护栏",
        "运行前检查运动禁忌和过度训练风险",
        "DRAFT",
        Map.of("phase", "BEFORE_MODEL", "mandatory", true));
  }

  private void seedComponent(
      String type,
      String key,
      String displayName,
      String description,
      String status,
      Map<String, Object> config) {
    jdbc.update(
        "INSERT INTO agent_component_projection(component_type,component_key,version,display_name,description,status,tags,config,source_checksum) VALUES (?,?,1,?,?,?,ARRAY['fitness'],?::jsonb,?) ON CONFLICT(component_type,component_key,version) DO NOTHING",
        type,
        key,
        displayName,
        description,
        status,
        write(config),
        "0".repeat(64));
  }

  private List<ProviderView> providers() {
    return jdbc.query(
        "SELECT c.component_key,c.display_name,c.config::text,CASE WHEN p.provider_key IS NULL THEN FALSE ELSE TRUE END AS configured FROM agent_component_projection c LEFT JOIN agent_provider_credentials p ON p.provider_key=c.component_key WHERE c.component_type='PROVIDER' ORDER BY c.component_key",
        (rs, row) -> {
          var config = read(rs.getString("config"), OBJECT_MAP);
          var configured = rs.getBoolean("configured");
          return new ProviderView(
              rs.getString("component_key"),
              rs.getString("display_name"),
              String.valueOf(config.getOrDefault("endpoint", "")),
              configured,
              configured ? MASK : "",
              configured ? "READY" : "NOT_CONFIGURED");
        });
  }

  private List<RunView> recentRuns() {
    return jdbc.query(
        "SELECT run_id FROM agent_runs ORDER BY started_at DESC LIMIT 20",
        (rs, row) -> runView(rs.getObject("run_id", UUID.class), false));
  }

  private RunView runView(UUID runId, boolean withEvents) {
    return jdbc
        .query(
            "SELECT * FROM agent_runs WHERE run_id=?",
            (rs, row) -> {
              var completed = rs.getTimestamp("completed_at");
              var events =
                  withEvents
                      ? jdbc.query(
                          "SELECT sequence,event_type,title,detail,occurred_at FROM agent_run_events WHERE run_id=? ORDER BY sequence",
                          (eventRs, eventRow) ->
                              new RunEventView(
                                  eventRs.getLong("sequence"),
                                  eventRs.getString("event_type"),
                                  eventRs.getString("title"),
                                  eventRs.getString("detail"),
                                  eventRs.getTimestamp("occurred_at").toInstant()),
                          runId)
                      : List.<RunEventView>of();
              return new RunView(
                  runId,
                  rs.getString("agent_key"),
                  rs.getInt("agent_version"),
                  rs.getString("status"),
                  rs.getTimestamp("started_at").toInstant(),
                  completed == null ? null : completed.toInstant(),
                  rs.getLong("duration_ms"),
                  rs.getInt("tool_calls"),
                  events);
            },
            runId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NotFound("运行记录不存在"));
  }

  private RowMapper<AgentDraftView> draftMapper() {
    return (rs, row) ->
        new AgentDraftView(
            rs.getString("agent_key"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("framework_key"),
            rs.getString("provider_key"),
            rs.getString("model_key"),
            rs.getString("prompt_key"),
            read(rs.getString("tool_keys"), STRING_LIST),
            read(rs.getString("skill_keys"), STRING_LIST),
            read(rs.getString("hook_keys"), STRING_LIST),
            rs.getString("memory_key"),
            rs.getDouble("temperature"),
            rs.getInt("max_tool_calls"),
            rs.getInt("current_published_version"),
            rs.getLong("revision"),
            rs.getTimestamp("updated_at").toInstant());
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("workbench payload cannot be serialized", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("stored workbench payload is invalid", exception);
    }
  }
}
