package happy.jayden.yang.agentbuilder.core.component;

public sealed interface VersionedComponent
    permits FrameworkRef,
        ProviderRef,
        ModelBinding,
        PromptRef,
        MemoryPolicyRef,
        OutputSchemaRef,
        EvaluationSuiteRef,
        DefaultProfileRef {
  ComponentMetadata metadata();

  default ComponentKey componentKey() {
    return metadata().componentKey();
  }

  default ComponentVersion version() {
    return metadata().version();
  }

  default ComponentStatus status() {
    return metadata().status();
  }

  default String componentChecksum() {
    return metadata().componentChecksum();
  }

  default PublishedComponentRef publishedRef() {
    return new PublishedComponentRef(componentKey(), version(), componentChecksum());
  }
}
