package happy.jayden.yang.agentbuilder.core.component.model;

import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.defaults.SparseModelParameters;
import java.util.List;
import java.util.Objects;

public record ModelDefinition(
    ComponentMetadata metadata,
    String modelId,
    ComponentRef providerRef,
    List<Modality> modalities,
    int contextWindow,
    int maxOutputTokens,
    Capabilities capabilities,
    SparseModelParameters defaultParameters,
    CatalogMetadata catalogMetadata)
    implements happy.jayden.yang.agentbuilder.core.component.CatalogComponent {
  public ModelDefinition {
    Objects.requireNonNull(metadata, "metadata");
    if (modelId == null || modelId.isBlank())
      throw new IllegalArgumentException("modelId must not be blank");
    Objects.requireNonNull(providerRef, "providerRef");
    modalities = List.copyOf(Objects.requireNonNull(modalities, "modalities"));
    if (modalities.isEmpty()
        || contextWindow < 1
        || maxOutputTokens < 1
        || maxOutputTokens > contextWindow)
      throw new IllegalArgumentException("invalid model limits");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(defaultParameters, "defaultParameters");
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
  }

  public record Capabilities(
      boolean toolCalling,
      boolean streaming,
      boolean structuredOutput,
      boolean vision,
      boolean audio) {}

  public enum Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO
  }
}
