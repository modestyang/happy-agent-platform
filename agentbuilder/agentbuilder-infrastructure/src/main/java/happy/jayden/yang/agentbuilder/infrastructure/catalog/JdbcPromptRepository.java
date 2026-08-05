package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.PromptRepository;
import happy.jayden.yang.agentbuilder.core.component.prompt.PromptVersion;
import java.util.List;
import javax.sql.DataSource;

public final class JdbcPromptRepository extends AbstractJdbcCatalogRepository<PromptVersion>
    implements PromptRepository {
  static final Class<PromptVersion> AGGREGATE_TYPE = PromptVersion.class;

  public JdbcPromptRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "prompt_catalog",
        "prompt_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.raw("template_format", prompt -> prompt.templateFormat().name()),
            CatalogColumn.raw("template", PromptVersion::template),
            CatalogColumn.json("variable_schema", PromptVersion::variables),
            CatalogColumn.raw("content_checksum", PromptVersion::contentChecksum)));
  }
}
