package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.CatalogFilter;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentStatus;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.prompt.PromptVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderPublicConfig;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationKey;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultProfileVersion;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import happy.jayden.yang.agentbuilder.infrastructure.security.AesGcmCredentialCipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CatalogRepositoryIntegrationTest {
  private static final String CHECKSUM = "a".repeat(64);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static DataSource dataSource;
  static ObjectMapper mapper;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    mapper = new ObjectMapper();
    try (var connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V1__agent_baseline.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V2__component_catalogs.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V3__default_profile_active_pointer.sql"));
    }
  }

  @Test
  void promptVersionsAreImmutableExactReadableFilterableAndOptimisticallyUpdated() {
    var repository = new JdbcPromptRepository(dataSource, mapper);
    var first = prompt(1, 1, "Hello {{name}}", ComponentStatus.AVAILABLE);
    var second = prompt(2, 1, "Welcome {{name}}", ComponentStatus.DRAFT);
    repository.create("fitness", first);
    repository.create("fitness", second);

    assertEquals(
        first,
        repository.find(new ComponentKey("prompt.coach"), new ComponentVersion(1)).orElseThrow());
    assertEquals(
        second,
        repository.find(new ComponentKey("prompt.coach"), new ComponentVersion(2)).orElseThrow());
    assertEquals(
        List.of(second),
        repository.list(
            new CatalogFilter(
                "fitness", Optional.of(ComponentStatus.DRAFT), Optional.of("fitness"))));
    assertThrows(IllegalStateException.class, () -> repository.create("fitness", first));
    var publishedReplacement = prompt(1, 2, "Changed", ComponentStatus.AVAILABLE);
    assertThrows(
        ImmutableCatalogVersionException.class, () -> repository.update(publishedReplacement, 1));

    var revised = prompt(2, 2, "Welcome back {{name}}", ComponentStatus.DRAFT);
    repository.update(revised, 1);
    assertThrows(OptimisticCatalogLockException.class, () -> repository.update(revised, 1));
    assertEquals(
        "Hello {{name}}",
        repository
            .find(new ComponentKey("prompt.coach"), new ComponentVersion(1))
            .orElseThrow()
            .template());
  }

  @Test
  void twoDefaultProfileVersionsPreserveHistoryAndSelectOnlyTheActiveVersion() {
    var repository = new JdbcDefaultProfileRepository(dataSource, mapper);
    var application = new ApplicationKey("fitness-profile-test");
    var first = profile(application, 1, 1, 2);
    var second = profile(application, 2, 1, 4);

    repository.create(first);
    repository.create(second);
    repository.activate(application, first.profile().componentKey(), first.profile().version(), 0);
    assertEquals(first, repository.findActive(application).orElseThrow());
    repository.activate(
        application, second.profile().componentKey(), second.profile().version(), 1);

    assertEquals(
        first,
        repository
            .find(new ComponentKey("defaults.fitness-profile-test"), new ComponentVersion(1))
            .orElseThrow());
    assertEquals(second, repository.findActive(application).orElseThrow());
    assertEquals(2, repository.findActivePointer(application).orElseThrow().revision());
    assertEquals(2, repository.list(CatalogFilter.application(application.value())).size());
    assertThrows(IllegalStateException.class, () -> repository.create(first));
    var publishedReplacement = profile(application, 2, 2, 99);
    assertThrows(
        ImmutableCatalogVersionException.class, () -> repository.update(publishedReplacement, 1));
    assertEquals(second.defaults(), repository.findActive(application).orElseThrow().defaults());
  }

  @Test
  void concurrentDefaultProfileActivationUsesPointerCasWithOneWinner() throws Exception {
    var repository = new JdbcDefaultProfileRepository(dataSource, mapper);
    var application = new ApplicationKey("fitness-profile-race");
    var first = profile(application, 1, 1, 1);
    var second = profile(application, 2, 1, 2);
    var third = profile(application, 3, 1, 3);
    repository.create(first);
    repository.create(second);
    repository.create(third);
    repository.activate(application, first.profile().componentKey(), first.profile().version(), 0);

    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var secondResult =
          executor.submit(() -> activateAfterBarrier(application, second, ready, start));
      var thirdResult =
          executor.submit(() -> activateAfterBarrier(application, third, ready, start));
      ready.await();
      start.countDown();
      assertEquals(
          1,
          java.util.stream.Stream.of(secondResult.get(), thirdResult.get())
              .filter(Boolean::booleanValue)
              .count());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(2, repository.findActivePointer(application).orElseThrow().revision());
  }

  @Test
  void providerCredentialPersistsOnlyAuthenticatedCiphertextAndMaskedJson() throws Exception {
    var provider = provider("provider.secure-test");
    var component =
        new ComponentRef(provider.metadata().componentKey(), provider.metadata().version());
    var keyFile = Files.createTempFile("catalog-master-key", ".txt");
    try {
      Files.writeString(
          keyFile, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
      var cipher =
          AesGcmCredentialCipher.fromEnvironment(
              Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, keyFile.toString()), component);
      var plaintext = "plain-secret-that-must-never-persist";
      var repository = new JdbcProviderRepository(dataSource, mapper);
      repository.create("fitness", provider, cipher.encrypt(plaintext.toCharArray()));

      var jdbc = new JdbcTemplate(dataSource);
      var ciphertextBefore =
          jdbc.queryForObject(
              "SELECT credential_ciphertext FROM provider_catalog WHERE component_key=? AND version=?",
              byte[].class,
              provider.metadata().componentKey().value(),
              provider.metadata().version().value());
      var stored =
          jdbc.queryForObject(
              "SELECT provider_payload::text || encode(credential_ciphertext,'escape') || encode(credential_iv,'escape') || encode(credential_aad,'escape') FROM provider_catalog WHERE component_key=? AND version=?",
              String.class,
              provider.metadata().componentKey().value(),
              provider.metadata().version().value());
      assertFalse(stored.contains(plaintext));
      assertTrue(stored.contains("provider.secure-test"));

      var encrypted =
          repository
              .findEncryptedCredential(
                  provider.metadata().componentKey(), provider.metadata().version())
              .orElseThrow();
      var decrypted = cipher.decrypt(encrypted);
      assertEquals(plaintext, new String(decrypted));
      java.util.Arrays.fill(decrypted, '\0');

      var response =
          repository
              .findMasked(provider.metadata().componentKey(), provider.metadata().version())
              .orElseThrow();
      var json = mapper.writeValueAsString(response);
      assertFalse(json.contains(plaintext));
      assertFalse(json.contains("ciphertext"));
      assertEquals("****", response.credential());

      var replacement = provider("provider.secure-test", 2);
      assertThrows(
          ImmutableCatalogVersionException.class,
          () ->
              repository.update(
                  replacement, cipher.encrypt("replacement-secret".toCharArray()), 1));
      assertArrayEquals(
          ciphertextBefore,
          jdbc.queryForObject(
              "SELECT credential_ciphertext FROM provider_catalog WHERE component_key=? AND version=?",
              byte[].class,
              provider.metadata().componentKey().value(),
              provider.metadata().version().value()));
    } finally {
      Files.deleteIfExists(keyFile);
    }
  }

  private static PromptVersion prompt(
      int version, long revision, String template, ComponentStatus status) {
    var metadata = metadata("prompt.coach", version, status);
    return new PromptVersion(
        metadata,
        catalog(metadata, revision),
        PromptVersion.TemplateFormat.MUSTACHE,
        template,
        List.of(new PromptVersion.Variable("name", PromptVersion.Type.STRING, true)),
        CHECKSUM);
  }

  private static DefaultProfileVersion profile(
      ApplicationKey application, int version, long revision, int maxToolCalls) {
    var metadata = metadata("defaults." + application.value(), version, ComponentStatus.AVAILABLE);
    return new DefaultProfileVersion(
        application,
        new DefaultProfileRef(metadata),
        DefaultValues.empty().withMaxToolCalls(maxToolCalls),
        revision,
        List.of("fitness"));
  }

  private static ProviderVersion provider(String key) {
    return provider(key, 1);
  }

  private static ProviderVersion provider(String key, long revision) {
    var metadata = metadata(key, 1, ComponentStatus.AVAILABLE);
    return new ProviderVersion(
        metadata,
        "Provider",
        "Description",
        List.of("fitness"),
        new ProviderVersion.CredentialReference(key, 1),
        "https://example.test",
        ProviderPublicConfig.empty(),
        catalog(metadata, revision));
  }

  private static boolean activateAfterBarrier(
      ApplicationKey application,
      DefaultProfileVersion target,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      new JdbcDefaultProfileRepository(dataSource, mapper)
          .activate(application, target.profile().componentKey(), target.profile().version(), 1);
      return true;
    } catch (OptimisticCatalogLockException expected) {
      return false;
    }
  }

  private static ComponentMetadata metadata(String key, int version, ComponentStatus status) {
    return new ComponentMetadata(
        new ComponentKey(key), new ComponentVersion(version), status, CHECKSUM);
  }

  private static CatalogMetadata catalog(ComponentMetadata metadata, long revision) {
    return new CatalogMetadata(
        metadata.componentKey().value(),
        "description",
        "catalog",
        List.of("fitness"),
        List.of(new ComponentRef(new ComponentKey("framework.main"), new ComponentVersion(1))),
        new CatalogMetadata.Source(CatalogMetadata.SourceType.INTERNAL, "test"),
        new CatalogMetadata.Audit("tester", Instant.EPOCH),
        revision);
  }
}
