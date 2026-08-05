package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.OutputSchemaRepository;
import happy.jayden.yang.agentbuilder.core.component.output.OutputSchemaVersion;
import java.util.List;
import javax.sql.DataSource;

public final class JdbcOutputSchemaRepository
    extends AbstractJdbcCatalogRepository<OutputSchemaVersion> implements OutputSchemaRepository {
  static final Class<OutputSchemaVersion> AGGREGATE_TYPE = OutputSchemaVersion.class;

  public JdbcOutputSchemaRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "output_schema_catalog",
        "output_schema_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.json("schema_payload", OutputSchemaVersion::schema),
            CatalogColumn.json("examples", OutputSchemaVersion::examples),
            CatalogColumn.raw("content_checksum", OutputSchemaVersion::contentChecksum)));
  }
}
