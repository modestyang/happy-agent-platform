package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.ProviderRepository;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

public final class JdbcProviderRepository extends AbstractJdbcCatalogRepository<ProviderVersion>
    implements ProviderRepository {
  static final Class<ProviderVersion> AGGREGATE_TYPE = ProviderVersion.class;

  public JdbcProviderRepository(DataSource dataSource, ObjectMapper mapper) {
    super(dataSource, mapper, "provider_catalog", "provider_payload", AGGREGATE_TYPE, List.of());
  }

  @Override
  public void create(String applicationScope, ProviderVersion aggregate) {
    ProviderRepository.super.create(applicationScope, aggregate);
  }

  @Override
  public void create(
      String applicationScope, ProviderVersion aggregate, EncryptedSecret credential) {
    requireIdentity(aggregate, credential);
    var metadata = aggregate.metadata();
    try {
      jdbc.update(
          "INSERT INTO provider_catalog(component_key,version,application_scope,status,revision,checksum,display_name,endpoint,public_config,provider_payload,credential_ciphertext,credential_iv,credential_aad,credential_key_version,tags) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::text[])",
          metadata.componentKey().value(),
          metadata.version().value(),
          applicationScope,
          metadata.status().name(),
          aggregate.catalogMetadata().revision(),
          metadata.componentChecksum(),
          aggregate.displayName(),
          aggregate.endpoint(),
          codec.write(aggregate.publicConfig()),
          codec.write(aggregate),
          credential.ciphertext(),
          credential.iv(),
          aad(credential.component()),
          aggregate.credentialReference().version(),
          postgresArray(aggregate.catalogMetadata().tags()));
    } catch (DuplicateKeyException exception) {
      throw new IllegalStateException("provider version already exists", exception);
    }
  }

  @Override
  public void update(ProviderVersion replacement, long expectedRevision) {
    ProviderRepository.super.update(replacement, expectedRevision);
  }

  @Override
  public void update(
      ProviderVersion replacement, EncryptedSecret credential, long expectedRevision) {
    requireIdentity(replacement, credential);
    if (replacement.catalogMetadata().revision() != expectedRevision + 1)
      throw new IllegalArgumentException("replacement revision must increment by one");
    var metadata = replacement.metadata();
    CatalogWriteGuard.updatedDraft(
        jdbc.update(
            ProviderSql.UPDATE_DRAFT,
            metadata.status().name(),
            replacement.catalogMetadata().revision(),
            metadata.componentChecksum(),
            replacement.displayName(),
            replacement.endpoint(),
            codec.write(replacement.publicConfig()),
            codec.write(replacement),
            credential.ciphertext(),
            credential.iv(),
            aad(credential.component()),
            replacement.credentialReference().version(),
            postgresArray(replacement.catalogMetadata().tags()),
            metadata.componentKey().value(),
            metadata.version().value(),
            expectedRevision),
        () ->
            jdbc
                .query(
                    "SELECT status FROM provider_catalog WHERE component_key=? AND version=?",
                    (resultSet, row) ->
                        happy.jayden.yang.agentbuilder.core.component.ComponentStatus.valueOf(
                            resultSet.getString(1)),
                    metadata.componentKey().value(),
                    metadata.version().value())
                .stream()
                .findFirst());
  }

  @Override
  public Optional<EncryptedSecret> findEncryptedCredential(
      ComponentKey key, ComponentVersion version) {
    var component = new ComponentRef(key, version);
    var expectedAad = aad(component);
    return jdbc
        .query(
            "SELECT credential_ciphertext,credential_iv,credential_aad FROM provider_catalog WHERE component_key=? AND version=?",
            (resultSet, row) -> {
              if (!MessageDigest.isEqual(expectedAad, resultSet.getBytes(3)))
                throw new SecurityException("stored credential AAD identity mismatch");
              return new EncryptedSecret(component, resultSet.getBytes(1), resultSet.getBytes(2));
            },
            key.value(),
            version.value())
        .stream()
        .findFirst();
  }

  private static void requireIdentity(ProviderVersion provider, EncryptedSecret secret) {
    var expected =
        new ComponentRef(provider.metadata().componentKey(), provider.metadata().version());
    if (!expected.equals(secret.component()))
      throw new IllegalArgumentException("credential AAD identity must match provider version");
  }

  private static byte[] aad(ComponentRef component) {
    return (component.componentKey().value() + "\u0000" + component.version().value())
        .getBytes(StandardCharsets.UTF_8);
  }
}
