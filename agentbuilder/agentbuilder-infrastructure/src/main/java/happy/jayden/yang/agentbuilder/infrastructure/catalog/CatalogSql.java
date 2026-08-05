package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import happy.jayden.yang.agentbuilder.core.catalog.CatalogFilter;
import java.util.ArrayList;
import java.util.List;

final class CatalogSql {
  private CatalogSql() {}

  static String insert(
      String table, String payloadColumn, List<? extends CatalogColumn<?>> columns) {
    return "INSERT INTO "
        + table
        + "(component_key,version,application_scope,status,revision,checksum,"
        + payloadColumn
        + ",tags"
        + columns.stream()
            .map(column -> "," + column.name())
            .collect(java.util.stream.Collectors.joining())
        + ") VALUES (?,?,?,?,?,?,?::jsonb,?::text[]"
        + columns.stream()
            .map(column -> ",?" + column.cast())
            .collect(java.util.stream.Collectors.joining())
        + ")";
  }

  static String exact(String table, String payloadColumn) {
    return "SELECT "
        + payloadColumn
        + "::text FROM "
        + table
        + " WHERE component_key=? AND version=?";
  }

  static Statement list(String table, String payloadColumn, CatalogFilter filter) {
    var sql =
        new StringBuilder("SELECT ")
            .append(payloadColumn)
            .append("::text FROM ")
            .append(table)
            .append(" WHERE application_scope=?");
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
    sql.append(" ORDER BY component_key,version");
    return new Statement(sql.toString(), arguments);
  }

  static String update(
      String table, String payloadColumn, List<? extends CatalogColumn<?>> columns) {
    return "UPDATE "
        + table
        + " SET status=?,revision=?,checksum=?,"
        + payloadColumn
        + "=?::jsonb,tags=?::text[]"
        + columns.stream()
            .map(column -> "," + column.name() + "=?" + column.cast())
            .collect(java.util.stream.Collectors.joining())
        + " WHERE component_key=? AND version=? AND revision=? AND status='DRAFT'";
  }

  record Statement(String sql, List<Object> arguments) {
    Statement {
      arguments = List.copyOf(arguments);
    }
  }
}
