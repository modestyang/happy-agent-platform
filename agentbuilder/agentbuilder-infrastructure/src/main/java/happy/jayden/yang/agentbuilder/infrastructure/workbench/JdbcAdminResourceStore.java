package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.service.workbench.AdminResourcePort;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for workbench resources that have independent lifecycle and failure boundaries. */
public final class JdbcAdminResourceStore implements AdminResourcePort {
  private static final String MASK = "••••••••";
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcAdminResourceStore(DataSource dataSource, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.mapper = mapper.copy().findAndRegisterModules();
  }

  @Override
  public List<ProviderDefinition> listProviders() {
    return jdbc.query(
        "SELECT p.provider_key,p.display_name,p.endpoint,p.protocol,p.status,p.revision,p.updated_at,"
            + "(c.provider_key IS NOT NULL) configured FROM agent_providers p "
            + "LEFT JOIN agent_provider_credentials c ON c.provider_key=p.provider_key "
            + "ORDER BY p.display_name,p.provider_key",
        (rs, row) ->
            new ProviderDefinition(
                rs.getString("provider_key"),
                rs.getString("display_name"),
                rs.getString("endpoint"),
                rs.getString("protocol"),
                rs.getString("status"),
                rs.getBoolean("configured"),
                rs.getBoolean("configured") ? MASK : "",
                rs.getLong("revision"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()));
  }

  @Override
  public Optional<ProviderDefinition> findProvider(String providerKey) {
    return listProviders().stream()
        .filter(item -> item.providerKey().equals(providerKey))
        .findFirst();
  }

  @Override
  public ProviderDefinition createProvider(ProviderCreate request) {
    try {
      jdbc.update(
          "INSERT INTO agent_providers(provider_key,display_name,endpoint,protocol,status) "
              + "VALUES (?,?,?,'OPENAI_COMPATIBLE','ACTIVE')",
          request.providerKey(),
          request.displayName(),
          request.endpoint());
    } catch (DataIntegrityViolationException exception) {
      throw new AdminWorkbenchPort.Conflict("Provider Key 已存在");
    }
    return requireProvider(request.providerKey());
  }

  @Override
  public ProviderDefinition updateProvider(
      String providerKey, ProviderUpdate request, long revision) {
    var changed =
        jdbc.update(
            "UPDATE agent_providers SET display_name=?,endpoint=?,status=?,revision=revision+1,"
                + "updated_at=CURRENT_TIMESTAMP WHERE provider_key=? AND revision=?",
            request.displayName(),
            request.endpoint(),
            request.status(),
            providerKey,
            revision);
    if (changed == 0)
      requireExistingOrConflict("Provider", providerKey, "agent_providers", "provider_key");
    return requireProvider(providerKey);
  }

  @Override
  public List<ModelDefinition> listModels(String providerKey) {
    var sql =
        "SELECT model_key,provider_key,model_id,display_name,description,supports_streaming,"
            + "supports_tool_calling,supports_vision,status,revision,updated_at FROM agent_models";
    if (providerKey == null || providerKey.isBlank()) {
      return jdbc.query(sql + " ORDER BY display_name,model_key", this::model);
    }
    return jdbc.query(
        sql + " WHERE provider_key=? ORDER BY display_name,model_key", this::model, providerKey);
  }

  @Override
  public ModelDefinition createModel(ModelCreate request) {
    try {
      jdbc.update(
          "INSERT INTO agent_models(model_key,provider_key,model_id,display_name,description,"
              + "supports_streaming,supports_tool_calling,supports_vision,status) VALUES (?,?,?,?,?,?,?,?,'ACTIVE')",
          request.modelKey(),
          request.providerKey(),
          request.modelId(),
          request.displayName(),
          request.description(),
          request.supportsStreaming(),
          request.supportsToolCalling(),
          request.supportsVision());
    } catch (DataIntegrityViolationException exception) {
      throw new AdminWorkbenchPort.Conflict("Model Key 或 Provider 内的 Model ID 已存在");
    }
    return requireModel(request.modelKey());
  }

  @Override
  public ModelDefinition updateModel(String modelKey, ModelUpdate request, long revision) {
    var changed =
        jdbc.update(
            "UPDATE agent_models SET model_id=?,display_name=?,description=?,supports_streaming=?,"
                + "supports_tool_calling=?,supports_vision=?,status=?,revision=revision+1,"
                + "updated_at=CURRENT_TIMESTAMP WHERE model_key=? AND revision=?",
            request.modelId(),
            request.displayName(),
            request.description(),
            request.supportsStreaming(),
            request.supportsToolCalling(),
            request.supportsVision(),
            request.status(),
            modelKey,
            revision);
    if (changed == 0) requireExistingOrConflict("Model", modelKey, "agent_models", "model_key");
    return requireModel(modelKey);
  }

  @Override
  public List<PromptDefinition> listPrompts() {
    return jdbc.query(
        "SELECT * FROM agent_prompts ORDER BY display_name,prompt_key",
        (rs, row) ->
            new PromptDefinition(
                rs.getString("prompt_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("template"),
                rs.getString("status"),
                rs.getLong("revision"),
                instant(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
  }

  @Override
  public PromptDefinition createPrompt(PromptCreate request) {
    try {
      jdbc.update(
          "INSERT INTO agent_prompts(prompt_key,display_name,description,template,status) VALUES (?,?,?,?,'ACTIVE')",
          request.promptKey(),
          request.displayName(),
          request.description(),
          request.template());
    } catch (DataIntegrityViolationException exception) {
      throw new AdminWorkbenchPort.Conflict("Prompt Key 已存在");
    }
    return listPrompts().stream()
        .filter(item -> item.promptKey().equals(request.promptKey()))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public PromptDefinition updatePrompt(String key, PromptUpdate request, long revision) {
    var changed =
        jdbc.update(
            "UPDATE agent_prompts SET display_name=?,description=?,template=?,status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE prompt_key=? AND revision=?",
            request.displayName(),
            request.description(),
            request.template(),
            request.status(),
            key,
            revision);
    if (changed == 0) requireExistingOrConflict("Prompt", key, "agent_prompts", "prompt_key");
    return listPrompts().stream()
        .filter(item -> item.promptKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public List<SkillDefinition> listSkills() {
    return jdbc.query(
        "SELECT *,required_tool_keys::text required_tools FROM agent_skills ORDER BY display_name,skill_key",
        (rs, row) ->
            new SkillDefinition(
                rs.getString("skill_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("when_to_use"),
                rs.getString("when_not_to_use"),
                rs.getString("content"),
                read(rs.getString("required_tools"), STRING_LIST),
                rs.getBoolean("runtime_ready"),
                rs.getString("status"),
                rs.getLong("revision"),
                instant(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
  }

  @Override
  public SkillDefinition createSkill(SkillCreate request) {
    try {
      jdbc.update(
          "INSERT INTO agent_skills(skill_key,display_name,description,when_to_use,when_not_to_use,content,required_tool_keys,runtime_ready,status) VALUES (?,?,?,?,?,?,?::jsonb,true,'ACTIVE')",
          request.skillKey(),
          request.displayName(),
          request.description(),
          request.whenToUse(),
          request.whenNotToUse(),
          request.content(),
          write(request.requiredToolKeys()));
    } catch (DataIntegrityViolationException exception) {
      throw new AdminWorkbenchPort.Conflict("Skill Key 已存在");
    }
    return listSkills().stream()
        .filter(item -> item.skillKey().equals(request.skillKey()))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public SkillDefinition updateSkill(String key, SkillUpdate request, long revision) {
    var changed =
        jdbc.update(
            "UPDATE agent_skills SET display_name=?,description=?,when_to_use=?,when_not_to_use=?,content=?,required_tool_keys=?::jsonb,status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE skill_key=? AND revision=?",
            request.displayName(),
            request.description(),
            request.whenToUse(),
            request.whenNotToUse(),
            request.content(),
            write(request.requiredToolKeys()),
            request.status(),
            key,
            revision);
    if (changed == 0) requireExistingOrConflict("Skill", key, "agent_skills", "skill_key");
    return listSkills().stream()
        .filter(item -> item.skillKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public List<HookDefinition> listHooks() {
    return jdbc.query(
        "SELECT * FROM agent_hooks ORDER BY display_name,hook_key",
        (rs, row) ->
            new HookDefinition(
                rs.getString("hook_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("phase"),
                rs.getBoolean("mandatory"),
                rs.getBoolean("runtime_ready"),
                rs.getString("status"),
                rs.getLong("revision"),
                instant(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
  }

  @Override
  public HookDefinition updateHook(String key, HookUpdate request, long revision) {
    var changed =
        jdbc.update(
            "UPDATE agent_hooks SET display_name=?,description=?,phase=?,mandatory=?,status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE hook_key=? AND revision=?",
            request.displayName(),
            request.description(),
            request.phase(),
            request.mandatory(),
            request.status(),
            key,
            revision);
    if (changed == 0) requireExistingOrConflict("Hook", key, "agent_hooks", "hook_key");
    return listHooks().stream()
        .filter(item -> item.hookKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public List<FrameworkDefinition> listFrameworks() {
    return jdbc.query(
        "SELECT *,capabilities::text capabilities_json FROM agent_frameworks ORDER BY display_name,framework_key",
        (rs, row) ->
            new FrameworkDefinition(
                rs.getString("framework_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                read(rs.getString("capabilities_json"), OBJECT_MAP),
                rs.getString("status"),
                rs.getLong("revision"),
                instant(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
  }

  @Override
  public List<MemoryDefinition> listMemories() {
    return jdbc.query(
        "SELECT * FROM agent_memories ORDER BY display_name,memory_key",
        (rs, row) ->
            new MemoryDefinition(
                rs.getString("memory_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getInt("retention_hours"),
                rs.getInt("max_tokens"),
                rs.getString("status"),
                rs.getLong("revision"),
                instant(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
  }

  private ModelDefinition model(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new ModelDefinition(
        rs.getString("model_key"),
        rs.getString("provider_key"),
        rs.getString("model_id"),
        rs.getString("display_name"),
        rs.getString("description"),
        rs.getBoolean("supports_streaming"),
        rs.getBoolean("supports_tool_calling"),
        rs.getBoolean("supports_vision"),
        rs.getString("status"),
        rs.getLong("revision"),
        instant(rs.getObject("updated_at", java.time.OffsetDateTime.class)));
  }

  private ProviderDefinition requireProvider(String providerKey) {
    return findProvider(providerKey)
        .orElseThrow(() -> new AdminWorkbenchPort.NotFound("Provider 不存在"));
  }

  private ModelDefinition requireModel(String modelKey) {
    return listModels("").stream()
        .filter(item -> item.modelKey().equals(modelKey))
        .findFirst()
        .orElseThrow(() -> new AdminWorkbenchPort.NotFound("Model 不存在"));
  }

  private void requireExistingOrConflict(String label, String key, String table, String column) {
    var count =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, key);
    if (count == null || count == 0) throw new AdminWorkbenchPort.NotFound(label + " 不存在");
    throw new AdminWorkbenchPort.Conflict(label + " 已被其他操作更新，请刷新后重试");
  }

  private <T> T read(String json, TypeReference<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception exception) {
      throw new IllegalStateException("Agent 资源 JSON 数据损坏", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Agent 资源无法序列化", exception);
    }
  }

  private static Instant instant(java.time.OffsetDateTime value) {
    return value.toInstant();
  }
}
