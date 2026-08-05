package happy.jayden.yang.agentbuilder.core.catalog;

import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.defaults.ActiveDefaultProfile;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationKey;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultProfileVersion;
import java.util.List;
import java.util.Optional;

public interface DefaultProfileRepository {
  void create(DefaultProfileVersion profile);

  Optional<DefaultProfileVersion> find(ComponentKey key, ComponentVersion version);

  Optional<DefaultProfileVersion> findActive(ApplicationKey applicationKey);

  Optional<ActiveDefaultProfile> findActivePointer(ApplicationKey applicationKey);

  ActiveDefaultProfile activate(
      ApplicationKey applicationKey,
      ComponentKey profileKey,
      ComponentVersion version,
      long expectedPointerRevision);

  List<DefaultProfileVersion> list(CatalogFilter filter);

  void update(DefaultProfileVersion replacement, long expectedRevision);
}
