package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.ModelRepository;
import happy.jayden.yang.agentbuilder.core.component.model.ModelDefinition;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public final class JdbcModelRepository extends AbstractJdbcCatalogRepository<ModelDefinition>
    implements ModelRepository {
  static final Class<ModelDefinition> AGGREGATE_TYPE = ModelDefinition.class;

  public JdbcModelRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "model_catalog",
        "model_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.raw("provider_key", model -> model.providerRef().componentKey().value()),
            CatalogColumn.raw("provider_version", model -> model.providerRef().version().value()),
            CatalogColumn.raw("model_id", ModelDefinition::modelId),
            CatalogColumn.textArray(
                "modalities", model -> model.modalities().stream().map(Enum::name).toList()),
            CatalogColumn.json("capabilities", ModelDefinition::capabilities),
            CatalogColumn.json(
                "limits",
                model ->
                    Map.of(
                        "contextWindow", model.contextWindow(),
                        "maxOutputTokens", model.maxOutputTokens(),
                        "defaultParameters", model.defaultParameters()))));
  }
}
