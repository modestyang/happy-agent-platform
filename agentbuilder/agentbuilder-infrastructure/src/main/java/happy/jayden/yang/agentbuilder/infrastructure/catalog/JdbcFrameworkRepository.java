package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.FrameworkRepository;
import happy.jayden.yang.agentbuilder.core.component.framework.FrameworkAdapterDefinition;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public final class JdbcFrameworkRepository
    extends AbstractJdbcCatalogRepository<FrameworkAdapterDefinition>
    implements FrameworkRepository {
  static final Class<FrameworkAdapterDefinition> AGGREGATE_TYPE = FrameworkAdapterDefinition.class;

  public JdbcFrameworkRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "framework_adapter_catalog",
        "framework_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.json(
                "capabilities",
                framework ->
                    Map.of(
                        "tools", framework.supportsTools(),
                        "skills", framework.supportsSkills(),
                        "hooks", framework.supportsHooks()))));
  }
}
