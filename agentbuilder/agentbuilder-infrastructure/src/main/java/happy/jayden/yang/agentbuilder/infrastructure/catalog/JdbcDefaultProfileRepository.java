package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.CatalogFilter;
import happy.jayden.yang.agentbuilder.core.catalog.DefaultProfileRepository;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentStatus;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.defaults.ActiveDefaultProfile;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationKey;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultProfileVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcDefaultProfileRepository implements DefaultProfileRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final CatalogJsonCodec codec;

  public JdbcDefaultProfileRepository(DataSource dataSource, ObjectMapper mapper) {
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    codec = new CatalogJsonCodec(mapper);
  }

  @Override
  public void create(DefaultProfileVersion profile) {
    try {
      var metadata = profile.profile().metadata();
      jdbc.update(
          "INSERT INTO default_profile_catalog(application_key,profile_key,version,status,revision,checksum,defaults,defaults_payload,tags) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::text[])",
          profile.applicationKey().value(),
          metadata.componentKey().value(),
          metadata.version().value(),
          metadata.status().name(),
          profile.revision(),
          metadata.componentChecksum(),
          codec.write(profile.defaults()),
          codec.write(profile),
          AbstractJdbcCatalogRepository.postgresArray(profile.tags()));
    } catch (DuplicateKeyException exception) {
      throw new IllegalStateException("default profile version already exists", exception);
    }
  }

  @Override
  public Optional<DefaultProfileVersion> find(ComponentKey key, ComponentVersion version) {
    return query(
            "SELECT defaults_payload::text FROM default_profile_catalog WHERE profile_key=? AND version=?",
            key.value(),
            version.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<DefaultProfileVersion> findActive(ApplicationKey applicationKey) {
    return query(DefaultProfileSql.FIND_ACTIVE, applicationKey.value()).stream().findFirst();
  }

  @Override
  public Optional<ActiveDefaultProfile> findActivePointer(ApplicationKey applicationKey) {
    return jdbc
        .query(
            "SELECT profile_key,version,revision FROM default_profile_active_pointer WHERE application_key=?",
            (resultSet, row) ->
                new ActiveDefaultProfile(
                    applicationKey,
                    new ComponentKey(resultSet.getString(1)),
                    new ComponentVersion(resultSet.getInt(2)),
                    resultSet.getLong(3)),
            applicationKey.value())
        .stream()
        .findFirst();
  }

  @Override
  public ActiveDefaultProfile activate(
      ApplicationKey applicationKey,
      ComponentKey profileKey,
      ComponentVersion version,
      long expectedPointerRevision) {
    if (expectedPointerRevision < 0)
      throw new IllegalArgumentException("expected pointer revision cannot be negative");
    return transactions.execute(
        ignored -> {
          var targetCount =
              jdbc.queryForObject(
                  "SELECT count(*) FROM default_profile_catalog WHERE application_key=? AND profile_key=? AND version=? AND status='AVAILABLE'",
                  Integer.class,
                  applicationKey.value(),
                  profileKey.value(),
                  version.value());
          if (targetCount == null || targetCount != 1)
            throw new IllegalArgumentException(
                "active default profile must be an AVAILABLE version");
          int changed;
          if (expectedPointerRevision == 0) {
            changed =
                jdbc.update(
                    DefaultProfileSql.INSERT_POINTER,
                    applicationKey.value(),
                    profileKey.value(),
                    version.value());
          } else {
            changed =
                jdbc.update(
                    DefaultProfileSql.UPDATE_POINTER,
                    profileKey.value(),
                    version.value(),
                    expectedPointerRevision + 1,
                    applicationKey.value(),
                    expectedPointerRevision);
          }
          CatalogWriteGuard.updated(changed);
          return new ActiveDefaultProfile(
              applicationKey, profileKey, version, expectedPointerRevision + 1);
        });
  }

  @Override
  public List<DefaultProfileVersion> list(CatalogFilter filter) {
    var sql =
        new StringBuilder(
            "SELECT defaults_payload::text FROM default_profile_catalog WHERE application_key=?");
    var arguments = new ArrayList<Object>();
    arguments.add(filter.applicationScope());
    filter
        .status()
        .ifPresent(
            status -> {
              sql.append(" AND status=?");
              arguments.add(status.name());
            });
    filter
        .tag()
        .ifPresent(
            tag -> {
              sql.append(" AND ?=ANY(tags)");
              arguments.add(tag);
            });
    sql.append(" ORDER BY profile_key,version");
    return query(sql.toString(), arguments.toArray());
  }

  @Override
  public void update(DefaultProfileVersion replacement, long expectedRevision) {
    if (replacement.revision() != expectedRevision + 1)
      throw new IllegalArgumentException("replacement revision must increment by one");
    var metadata = replacement.profile().metadata();
    CatalogWriteGuard.updatedDraft(
        jdbc.update(
            DefaultProfileSql.UPDATE_DRAFT,
            replacement.applicationKey().value(),
            metadata.status().name(),
            replacement.revision(),
            metadata.componentChecksum(),
            codec.write(replacement.defaults()),
            codec.write(replacement),
            AbstractJdbcCatalogRepository.postgresArray(replacement.tags()),
            metadata.componentKey().value(),
            metadata.version().value(),
            expectedRevision),
        () -> persistedStatus(metadata.componentKey(), metadata.version()));
  }

  private List<DefaultProfileVersion> query(String sql, Object... arguments) {
    return jdbc.query(
        sql,
        (resultSet, row) -> codec.read(resultSet.getString(1), DefaultProfileVersion.class),
        arguments);
  }

  private Optional<ComponentStatus> persistedStatus(ComponentKey key, ComponentVersion version) {
    return jdbc
        .query(
            "SELECT status FROM default_profile_catalog WHERE profile_key=? AND version=?",
            (resultSet, row) -> ComponentStatus.valueOf(resultSet.getString(1)),
            key.value(),
            version.value())
        .stream()
        .findFirst();
  }
}
