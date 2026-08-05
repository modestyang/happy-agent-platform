package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.MemoryPolicyRepository;
import happy.jayden.yang.agentbuilder.core.component.memory.MemoryPolicyVersion;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public final class JdbcMemoryPolicyRepository
    extends AbstractJdbcCatalogRepository<MemoryPolicyVersion> implements MemoryPolicyRepository {
  static final Class<MemoryPolicyVersion> AGGREGATE_TYPE = MemoryPolicyVersion.class;

  public JdbcMemoryPolicyRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "memory_policy_catalog",
        "memory_policy_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.raw("policy_type", memory -> memory.policyType().name()),
            CatalogColumn.raw("compression", memory -> memory.compression().name()),
            CatalogColumn.json(
                "policy_config",
                memory ->
                    Map.of(
                        "maxTokens", memory.maxTokens(),
                        "retentionDays", memory.retentionDays(),
                        "compressionThresholdTokens", memory.compressionThresholdTokens(),
                        "compressionWindowMessages", memory.compressionWindowMessages(),
                        "configSchema", memory.configSchema(),
                        "defaults", memory.defaults()))));
  }
}
