package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.CatalogFilter;
import happy.jayden.yang.agentbuilder.core.component.CatalogComponent;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class AbstractJdbcCatalogRepository<T extends CatalogComponent> {
  private final String table;
  private final String payloadColumn;
  private final Class<T> aggregateType;
  private final List<CatalogColumn<T>> columns;
  protected final JdbcTemplate jdbc;
  protected final CatalogJsonCodec codec;

  AbstractJdbcCatalogRepository(
      DataSource dataSource,
      ObjectMapper mapper,
      String table,
      String payloadColumn,
      Class<T> type,
      List<CatalogColumn<T>> columns) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.codec = new CatalogJsonCodec(mapper);
    this.table = table;
    this.payloadColumn = payloadColumn;
    this.aggregateType = type;
    this.columns = List.copyOf(columns);
  }

  public void create(String applicationScope, T aggregate) {
    var metadata = aggregate.metadata();
    var catalog = aggregate.catalogMetadata();
    try {
      var arguments = new java.util.ArrayList<Object>();
      java.util.Collections.addAll(
          arguments,
          metadata.componentKey().value(),
          metadata.version().value(),
          applicationScope,
          metadata.status().name(),
          catalog.revision(),
          metadata.componentChecksum(),
          codec.write(aggregate),
          postgresArray(catalog.tags()));
      columns.forEach(column -> arguments.add(column.argument(aggregate, codec)));
      jdbc.update(CatalogSql.insert(table, payloadColumn, columns), arguments.toArray());
    } catch (DuplicateKeyException exception) {
      throw new IllegalStateException("catalog version already exists", exception);
    }
  }

  public final Optional<T> find(ComponentKey key, ComponentVersion version) {
    var values =
        jdbc.query(
            CatalogSql.exact(table, payloadColumn),
            (resultSet, row) -> codec.read(resultSet.getString(1), aggregateType),
            key.value(),
            version.value());
    return values.stream().findFirst();
  }

  public final List<T> list(CatalogFilter filter) {
    var statement = CatalogSql.list(table, payloadColumn, filter);
    return jdbc.query(
        statement.sql(),
        (resultSet, row) -> codec.read(resultSet.getString(1), aggregateType),
        statement.arguments().toArray());
  }

  public void update(T replacement, long expectedRevision) {
    var metadata = replacement.metadata();
    var catalog = replacement.catalogMetadata();
    if (catalog.revision() != expectedRevision + 1)
      throw new IllegalArgumentException("replacement revision must increment by one");
    var arguments = new java.util.ArrayList<Object>();
    java.util.Collections.addAll(
        arguments,
        metadata.status().name(),
        catalog.revision(),
        metadata.componentChecksum(),
        codec.write(replacement),
        postgresArray(catalog.tags()));
    columns.forEach(column -> arguments.add(column.argument(replacement, codec)));
    java.util.Collections.addAll(
        arguments, metadata.componentKey().value(), metadata.version().value(), expectedRevision);
    CatalogWriteGuard.updatedDraft(
        jdbc.update(CatalogSql.update(table, payloadColumn, columns), arguments.toArray()),
        () -> persistedStatus(metadata.componentKey(), metadata.version()));
  }

  static String postgresArray(List<String> values) {
    return values.stream()
        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }

  private Optional<happy.jayden.yang.agentbuilder.core.component.ComponentStatus> persistedStatus(
      ComponentKey key, ComponentVersion version) {
    return jdbc
        .query(
            "SELECT status FROM " + table + " WHERE component_key=? AND version=?",
            (resultSet, row) ->
                happy.jayden.yang.agentbuilder.core.component.ComponentStatus.valueOf(
                    resultSet.getString(1)),
            key.value(),
            version.value())
        .stream()
        .findFirst();
  }
}
