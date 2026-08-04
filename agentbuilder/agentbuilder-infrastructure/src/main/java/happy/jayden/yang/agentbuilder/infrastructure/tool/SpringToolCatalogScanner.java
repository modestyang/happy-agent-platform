package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.ToolBuildManifest;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolManifestEntry;
import happy.jayden.yang.agentbuilder.core.tool.ToolSchema;
import happy.jayden.yang.agentbuilder.core.tool.ToolSourceType;
import happy.jayden.yang.agentbuilder.core.tool.ToolVersionReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.util.ClassUtils;

public final class SpringToolCatalogScanner {

  private final String registeredBuild;
  private final Map<String, ToolDescriptor> historicalVersions;
  private final ToolSchemaGenerator schemas = new ToolSchemaGenerator();
  private final ToolContractChecksum checksums = new ToolContractChecksum();

  public SpringToolCatalogScanner(
      String registeredBuild, Collection<ToolDescriptor> historicalMetadata) {
    this.registeredBuild = requireText(registeredBuild, "registeredBuild");
    Objects.requireNonNull(historicalMetadata, "historicalMetadata");
    historicalVersions = new HashMap<>();
    for (var descriptor : historicalMetadata) {
      Objects.requireNonNull(descriptor, "historicalMetadata item");
      var previous = historicalVersions.put(identity(descriptor), descriptor);
      if (previous != null) {
        throw new IllegalArgumentException(
            "historical metadata contains duplicate Tool version " + identity(descriptor));
      }
    }
  }

  public ToolDescriptor scan(Object bean) {
    Objects.requireNonNull(bean, "bean");
    var descriptors = scanAll(List.of(bean));
    if (descriptors.size() != 1) {
      throw new IllegalArgumentException(
          "expected exactly one @AgentTool method but found " + descriptors.size());
    }
    return descriptors.get(0);
  }

  public List<ToolDescriptor> scan(ListableBeanFactory beanFactory) {
    Objects.requireNonNull(beanFactory, "beanFactory");
    var beans = new ArrayList<>();
    Arrays.stream(beanFactory.getBeanDefinitionNames())
        .sorted()
        .forEach(name -> beans.add(beanFactory.getBean(name)));
    return scanAll(beans);
  }

  public List<ToolDescriptor> scanAll(Collection<?> beans) {
    Objects.requireNonNull(beans, "beans");
    var descriptors = new ArrayList<ToolDescriptor>();
    for (var bean : beans) {
      Objects.requireNonNull(bean, "beans item");
      var beanType = ClassUtils.getUserClass(AopUtils.getTargetClass(bean));
      for (var method : annotatedMethods(beanType)) {
        descriptors.add(descriptor(method, method.getAnnotation(AgentTool.class)));
      }
    }
    descriptors.sort(
        Comparator.comparing(ToolDescriptor::toolKey)
            .thenComparingInt(ToolDescriptor::contractVersion));
    validateDiscoverySet(descriptors);
    descriptors.forEach(this::validateHistoricalContract);
    return List.copyOf(descriptors);
  }

  public ToolBuildManifest buildManifest(Collection<ToolDescriptor> descriptors) {
    Objects.requireNonNull(descriptors, "descriptors");
    var entries =
        descriptors.stream()
            .map(
                descriptor ->
                    new ToolManifestEntry(
                        descriptor.toolKey(),
                        descriptor.contractVersion(),
                        descriptor.schemaChecksum()))
            .sorted(
                Comparator.comparing(ToolManifestEntry::toolKey)
                    .thenComparingInt(ToolManifestEntry::contractVersion))
            .toList();
    return new ToolBuildManifest(registeredBuild, entries);
  }

  private ToolDescriptor descriptor(Method method, AgentTool metadata) {
    var inputSchema = schemas.inputSchema(method);
    var outputSchema = schemas.outputSchema(method, metadata.outputDescription());
    var replacement = replacement(metadata);
    var tags = List.of(metadata.tags());
    var scopes = List.of(metadata.requiredScopes());
    var contract =
        contract(
            metadata,
            inputSchema,
            outputSchema,
            tags.stream().sorted().toList(),
            scopes.stream().sorted().toList());
    var checksum = checksums.calculate(contract);
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
        replacement,
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

  private static List<Method> annotatedMethods(Class<?> beanType) {
    var methods = new LinkedHashSet<Method>();
    for (var current = beanType;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      Arrays.stream(current.getDeclaredMethods())
          .filter(method -> !method.isBridge() && !method.isSynthetic())
          .filter(method -> method.isAnnotationPresent(AgentTool.class))
          .forEach(methods::add);
    }
    for (var implemented : beanType.getInterfaces()) {
      Arrays.stream(implemented.getMethods())
          .filter(method -> method.isAnnotationPresent(AgentTool.class))
          .forEach(methods::add);
    }
    return methods.stream().sorted(Comparator.comparing(Method::toGenericString)).toList();
  }

  private static void validateDiscoverySet(List<ToolDescriptor> descriptors) {
    var runtimeNames = new HashMap<String, ToolDescriptor>();
    var versions = new HashMap<String, ToolDescriptor>();
    for (var descriptor : descriptors) {
      var runtimeCollision = runtimeNames.putIfAbsent(descriptor.runtimeName(), descriptor);
      if (runtimeCollision != null) {
        throw new IllegalArgumentException(
            "duplicate runtimeName "
                + descriptor.runtimeName()
                + " for "
                + runtimeCollision.toolKey()
                + " and "
                + descriptor.toolKey());
      }
      var duplicateVersion = versions.putIfAbsent(identity(descriptor), descriptor);
      if (duplicateVersion != null) {
        throw new IllegalArgumentException(
            "duplicate Tool contract version " + identity(descriptor));
      }
    }
  }

  private void validateHistoricalContract(ToolDescriptor descriptor) {
    var historical = historicalVersions.get(identity(descriptor));
    if (historical != null && !historical.schemaChecksum().equals(descriptor.schemaChecksum())) {
      throw new IllegalStateException(
          "Tool contract "
              + identity(descriptor)
              + " changed checksum; increment contractVersion before deployment");
    }
  }

  private static String identity(ToolDescriptor descriptor) {
    return descriptor.toolKey() + "@" + descriptor.contractVersion();
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
