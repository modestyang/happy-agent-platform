package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationKey;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultProfileVersion;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CatalogMigrationIntegrationTest {
  private static final String CHECKSUM = "a".repeat(64);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static DataSource dataSource;

  @BeforeAll
  static void migrateThroughV2() throws Exception {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (var connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V1__agent_baseline.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V2__component_catalogs.sql"));
    }
  }

  @Test
  void v3BackfillsLegacyActiveProfileRemovesLegacyJsonAndEnforcesCompositeOwnership()
      throws Exception {
    var application = new ApplicationKey("migration-fitness");
    var profile = profile(application);
    var codec = CatalogJsonCodec.standard();
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO default_profile_catalog(application_key,profile_key,version,status,revision,checksum,defaults,defaults_payload,tags,active) VALUES (?,?,1,'AVAILABLE',1,?,?::jsonb,(?::jsonb || '{\"active\":true}'::jsonb),?::text[],true)",
        application.value(),
        profile.profile().componentKey().value(),
        CHECKSUM,
        codec.write(profile.defaults()),
        codec.write(profile),
        AbstractJdbcCatalogRepository.postgresArray(profile.tags()));

    try (var connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V3__default_profile_active_pointer.sql"));
    }

    var repository = new JdbcDefaultProfileRepository(dataSource, new ObjectMapper());
    assertEquals(profile, repository.findActive(application).orElseThrow());
    assertEquals(1, repository.findActivePointer(application).orElseThrow().revision());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name='default_profile_catalog' AND column_name='active'",
            Integer.class));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO default_profile_active_pointer(application_key,profile_key,version,revision) VALUES ('another-application',?,?,1)",
                profile.profile().componentKey().value(),
                profile.profile().version().value()));
  }

  private static DefaultProfileVersion profile(ApplicationKey application) {
    var metadata =
        ComponentMetadata.available(
            new ComponentKey("defaults.migration"), new ComponentVersion(1), CHECKSUM);
    return new DefaultProfileVersion(
        application,
        new DefaultProfileRef(metadata),
        DefaultValues.empty().withMaxToolCalls(3),
        1,
        List.of("migration"));
  }
}
