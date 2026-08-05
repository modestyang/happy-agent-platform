package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.HookRepository;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import java.util.List;
import javax.sql.DataSource;

public final class JdbcHookRepository extends AbstractJdbcCatalogRepository<HookDefinition>
    implements HookRepository {
  static final Class<HookDefinition> AGGREGATE_TYPE = HookDefinition.class;

  public JdbcHookRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "hook_catalog",
        "hook_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.raw("hook_type", HookDefinition::type),
            CatalogColumn.textArray(
                "phases", hook -> hook.phases().stream().map(Enum::name).sorted().toList()),
            CatalogColumn.raw("execution_order", HookDefinition::order),
            CatalogColumn.json("config_schema", HookDefinition::configSchema),
            CatalogColumn.raw("failure_policy", hook -> hook.failurePolicy().name()),
            CatalogColumn.raw("mandatory", HookDefinition::mandatory)));
  }
}
