package happy.jayden.yang.agentbuilder.core.component.provider;

import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import java.util.Objects;

public record ProviderVersion(
    ComponentMetadata metadata,
    String displayName,
    String description,
    java.util.List<String> tags,
    CredentialReference credentialReference,
    String endpoint,
    ProviderPublicConfig publicConfig,
    CatalogMetadata catalogMetadata)
    implements happy.jayden.yang.agentbuilder.core.component.CatalogComponent {
  public ProviderVersion {
    Objects.requireNonNull(metadata, "metadata");
    text(displayName, "displayName");
    text(description, "description");
    tags = java.util.List.copyOf(Objects.requireNonNull(tags, "tags"));
    Objects.requireNonNull(credentialReference, "credentialReference");
    text(endpoint, "endpoint");
    Objects.requireNonNull(publicConfig, "publicConfig");
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
  }

  public MaskedResponse maskedResponse() {
    return new MaskedResponse(
        metadata, displayName, description, tags, endpoint, publicConfig, "****");
  }

  public record CredentialReference(String secretKey, int version) {
    public CredentialReference {
      text(secretKey, "secretKey");
      if (version < 1) throw new IllegalArgumentException("version must be positive");
    }
  }

  public record MaskedResponse(
      ComponentMetadata metadata,
      String displayName,
      String description,
      java.util.List<String> tags,
      String endpoint,
      ProviderPublicConfig publicConfig,
      String credential) {
    public MaskedResponse {
      Objects.requireNonNull(metadata, "metadata");
      text(displayName, "displayName");
      text(description, "description");
      tags = java.util.List.copyOf(Objects.requireNonNull(tags, "tags"));
      text(endpoint, "endpoint");
      Objects.requireNonNull(publicConfig, "publicConfig");
      if (!"****".equals(credential))
        throw new IllegalArgumentException("credential response must be masked");
    }
  }

  private static void text(String value, String name) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(name + " must not be blank");
  }
}
