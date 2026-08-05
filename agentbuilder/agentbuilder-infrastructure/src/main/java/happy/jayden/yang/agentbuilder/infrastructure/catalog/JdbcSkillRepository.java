package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.catalog.SkillRepository;
import happy.jayden.yang.agentbuilder.core.component.skill.SkillDefinition;
import java.util.List;
import javax.sql.DataSource;

public final class JdbcSkillRepository extends AbstractJdbcCatalogRepository<SkillDefinition>
    implements SkillRepository {
  static final Class<SkillDefinition> AGGREGATE_TYPE = SkillDefinition.class;

  public JdbcSkillRepository(DataSource dataSource, ObjectMapper mapper) {
    super(
        dataSource,
        mapper,
        "skill_catalog",
        "skill_payload",
        AGGREGATE_TYPE,
        List.of(
            CatalogColumn.raw("markdown", SkillDefinition::markdown),
            CatalogColumn.json("resources", SkillDefinition::resources),
            CatalogColumn.json("disclosure", SkillDefinition::disclosure),
            CatalogColumn.json("required_tools", SkillDefinition::requiredTools)));
  }
}
