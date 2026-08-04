package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolSchema;
import happy.jayden.yang.agentbuilder.core.tool.ToolSourceType;
import happy.jayden.yang.agentbuilder.core.tool.ToolVersionReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ToolDescriptorFactory {

  private final ToolSchemaGenerator schemas = new ToolSchemaGenerator();
  private final ToolContractChecksum checksums = new ToolContractChecksum();

  ToolDescriptor create(ToolMethodDefinition method, String registeredBuild) {
    var metadata = method.metadata();
    var inputSchema = schemas.inputSchema(method);
    var outputSchema = schemas.outputSchema(method.contractMethod(), metadata.outputDescription());
    var tags = List.of(metadata.tags());
    var scopes = List.of(metadata.requiredScopes());
    var checksum =
        checksums.calculate(
            contract(
                metadata,
                inputSchema,
                outputSchema,
                tags.stream().sorted().toList(),
                scopes.stream().sorted().toList()));
    return new ToolDescriptor(
        metadata.key(),
        metadata.version(),
        metadata.runtimeName(),
        metadata.displayName(),
        metadata.description(),
        metadata.whenToUse(),
        metadata.whenNotToUse(),
        metadata.applicationKey(),
        metadata.group(),
        tags,
        inputSchema,
        outputSchema,
        true,
        metadata.sideEffect(),
        metadata.idempotent(),
        metadata.risk(),
        scopes,
        metadata.defaultTimeoutMs(),
        metadata.maxTimeoutMs(),
        metadata.defaultMaxCallsPerRun(),
        metadata.supportsStreaming(),
        metadata.returnDirect(),
        ToolSourceType.LOCAL_BEAN,
        checksum,
        metadata.status(),
        replacement(metadata),
        registeredBuild);
  }

  private static Map<String, Object> contract(
      AgentTool metadata,
      ToolSchema inputSchema,
      ToolSchema outputSchema,
      List<String> tags,
      List<String> scopes) {
    var contract = new LinkedHashMap<String, Object>();
    contract.put("toolKey", metadata.key());
    contract.put("contractVersion", metadata.version());
    contract.put("runtimeName", metadata.runtimeName());
    contract.put("displayName", metadata.displayName());
    contract.put("description", metadata.description());
    contract.put("whenToUse", metadata.whenToUse());
    contract.put("whenNotToUse", metadata.whenNotToUse());
    contract.put("applicationKey", metadata.applicationKey());
    contract.put("group", metadata.group());
    contract.put("tags", tags);
    contract.put("inputSchema", inputSchema.document());
    contract.put("outputSchema", outputSchema.document());
    contract.put("strictInput", true);
    contract.put("sideEffect", metadata.sideEffect().name());
    contract.put("idempotent", metadata.idempotent());
    contract.put("riskLevel", metadata.risk().name());
    contract.put("requiredScopes", scopes);
    contract.put("defaultTimeoutMs", metadata.defaultTimeoutMs());
    contract.put("maxTimeoutMs", metadata.maxTimeoutMs());
    contract.put("defaultMaxCallsPerRun", metadata.defaultMaxCallsPerRun());
    contract.put("supportsStreaming", metadata.supportsStreaming());
    contract.put("returnDirect", metadata.returnDirect());
    contract.put("sourceType", ToolSourceType.LOCAL_BEAN.name());
    return contract;
  }

  private static Optional<ToolVersionReference> replacement(AgentTool metadata) {
    var hasKey = !metadata.replacementKey().isBlank();
    var hasVersion = metadata.replacementVersion() > 0;
    if (hasKey != hasVersion) {
      throw new IllegalArgumentException(
          "replacementKey and replacementVersion must both be present or absent");
    }
    return hasKey
        ? Optional.of(
            new ToolVersionReference(metadata.replacementKey(), metadata.replacementVersion()))
        : Optional.empty();
  }
}
