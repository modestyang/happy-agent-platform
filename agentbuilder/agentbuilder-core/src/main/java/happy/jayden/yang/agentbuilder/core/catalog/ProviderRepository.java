package happy.jayden.yang.agentbuilder.core.catalog;

import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import java.util.Optional;

public interface ProviderRepository extends TypedCatalogRepository<ProviderVersion> {
  void create(String applicationScope, ProviderVersion aggregate, EncryptedSecret credential);

  void update(ProviderVersion replacement, EncryptedSecret credential, long expectedRevision);

  Optional<EncryptedSecret> findEncryptedCredential(ComponentKey key, ComponentVersion version);

  default Optional<ProviderVersion.MaskedResponse> findMasked(
      ComponentKey key, ComponentVersion version) {
    return find(key, version).map(ProviderVersion::maskedResponse);
  }

  @Override
  default void create(String applicationScope, ProviderVersion aggregate) {
    throw new IllegalArgumentException("provider credentials must be supplied as ciphertext");
  }

  @Override
  default void update(ProviderVersion replacement, long expectedRevision) {
    throw new IllegalArgumentException("provider credentials must be supplied as ciphertext");
  }
}
