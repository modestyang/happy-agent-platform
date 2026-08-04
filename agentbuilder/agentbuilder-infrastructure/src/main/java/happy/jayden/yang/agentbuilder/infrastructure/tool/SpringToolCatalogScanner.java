package happy.jayden.yang.agentbuilder.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.tool.ToolBuildManifest;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolLifecycleStatus;
import happy.jayden.yang.agentbuilder.core.tool.ToolManifestEntry;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistration;
import happy.jayden.yang.agentbuilder.core.tool.ToolText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ListableBeanFactory;

public final class SpringToolCatalogScanner {

  private final String registeredBuild;
  private final ToolContractHistory history;
  private final ToolDescriptorFactory descriptors = new ToolDescriptorFactory();
  private final SpringToolMethodDiscovery methodDiscovery = new SpringToolMethodDiscovery();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private List<ToolRegistration> currentRegistrations = List.of();

  public SpringToolCatalogScanner(
      String registeredBuild, Collection<ToolDescriptor> completeHistoricalMetadata) {
    this.registeredBuild = ToolText.require(registeredBuild, 1, 160, "registeredBuild");
    history = new ToolContractHistory(completeHistoricalMetadata);
  }

  public synchronized ToolDescriptor scan(Object bean) {
    return scanRegistration(bean).descriptor();
  }

  public synchronized ToolRegistration scanRegistration(Object bean) {
    Objects.requireNonNull(bean, "bean");
    var registrations = scanRegistrations(List.of(bean));
    if (registrations.size() != 1) {
      throw new IllegalArgumentException(
          "expected exactly one @AgentTool method but found " + registrations.size());
    }
    return registrations.get(0);
  }

  public synchronized List<ToolDescriptor> scan(ListableBeanFactory beanFactory) {
    return metadata(scanRegistrations(beanFactory));
  }

  public synchronized List<ToolRegistration> scanRegistrations(ListableBeanFactory beanFactory) {
    Objects.requireNonNull(beanFactory, "beanFactory");
    var beans = new ArrayList<>();
    Arrays.stream(beanFactory.getBeanDefinitionNames())
        .sorted()
        .forEach(name -> beans.add(beanFactory.getBean(name)));
    return scanRegistrations(beans);
  }

  public synchronized List<ToolDescriptor> scanAll(Collection<?> beans) {
    return metadata(scanRegistrations(beans));
  }

  public synchronized List<ToolRegistration> scanRegistrations(Collection<?> beans) {
    Objects.requireNonNull(beans, "beans");
    currentRegistrations = List.of();
    var methods = new ArrayList<ToolMethodDefinition>();
    for (var bean : beans) {
      methods.addAll(methodDiscovery.discover(Objects.requireNonNull(bean, "beans item")));
    }
    methods.sort(
        Comparator.comparing((ToolMethodDefinition value) -> value.metadata().key())
            .thenComparingInt(value -> value.metadata().version())
            .thenComparing(value -> value.contractMethod().toGenericString()));

    var registrations = new ArrayList<ToolRegistration>();
    for (var method : methods) {
      var descriptor = descriptors.create(method, registeredBuild);
      registrations.add(
          new ToolRegistration(
              descriptor, new ReflectiveAgentToolHandler(method, descriptor, objectMapper)));
    }
    validateDiscoverySet(registrations);
    registrations.forEach(registration -> history.validate(registration.descriptor()));
    currentRegistrations = List.copyOf(registrations);
    return currentRegistrations;
  }

  public synchronized ToolBuildManifest buildManifest() {
    if (currentRegistrations.isEmpty()) {
      throw new IllegalStateException("scan current executable Tool registrations before manifest");
    }
    var entries = new ArrayList<ToolManifestEntry>();
    for (var registration : currentRegistrations) {
      var descriptor = registration.descriptor();
      if (!descriptor.registeredBuild().equals(registeredBuild)) {
        throw new IllegalStateException("Tool registration build does not match scanner build");
      }
      if (descriptor.status() != ToolLifecycleStatus.AVAILABLE) {
        throw new IllegalStateException(
            "only AVAILABLE Tool registrations can enter a build manifest: "
                + identity(descriptor));
      }
      Objects.requireNonNull(registration.handler(), "current Tool registration handler");
      entries.add(
          new ToolManifestEntry(
              descriptor.toolKey(), descriptor.contractVersion(), descriptor.schemaChecksum()));
    }
    entries.sort(
        Comparator.comparing(ToolManifestEntry::toolKey)
            .thenComparingInt(ToolManifestEntry::contractVersion));
    return new ToolBuildManifest(registeredBuild, entries);
  }

  private static void validateDiscoverySet(List<ToolRegistration> registrations) {
    var runtimeNames = new HashMap<String, ToolDescriptor>();
    var versions = new HashMap<String, ToolDescriptor>();
    for (var registration : registrations) {
      var descriptor = registration.descriptor();
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
      if (versions.putIfAbsent(identity(descriptor), descriptor) != null) {
        throw new IllegalArgumentException(
            "duplicate Tool contract version " + identity(descriptor));
      }
    }
  }

  private static List<ToolDescriptor> metadata(List<ToolRegistration> registrations) {
    return registrations.stream().map(ToolRegistration::descriptor).toList();
  }

  private static String identity(ToolDescriptor descriptor) {
    return descriptor.toolKey() + "@" + descriptor.contractVersion();
  }
}
