package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.EvaluationSuiteRepository;
import happy.jayden.yang.agentbuilder.core.component.evaluation.EvaluationSuiteVersion;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public final class JdbcEvaluationSuiteRepository
    extends AbstractJdbcCatalogRepository<EvaluationSuiteVersion>
    implements EvaluationSuiteRepository {
  static final Class<EvaluationSuiteVersion> AGGREGATE_TYPE = EvaluationSuiteVersion.class;

  public JdbcEvaluationSuiteRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "evaluation_suite_catalog",
        "evaluation_suite_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.json("cases", EvaluationSuiteVersion::cases),
            CatalogColumn.json(
                "scoring_config",
                suite ->
                    Map.of(
                        "minimumScore", suite.minimumScore(),
                        "scoringRule", suite.scoringRule())),
            CatalogColumn.json(
                "safety_config",
                suite ->
                    Map.of(
                        "safetyGate", suite.safetyGate(),
                        "criteria", suite.safetyCriteria())),
            CatalogColumn.raw("content_checksum", EvaluationSuiteVersion::contentChecksum)));
  }
}
