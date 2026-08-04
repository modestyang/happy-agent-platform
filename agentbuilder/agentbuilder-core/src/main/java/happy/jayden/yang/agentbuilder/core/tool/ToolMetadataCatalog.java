package happy.jayden.yang.agentbuilder.core.tool;

import java.util.List;
import java.util.Optional;

public interface ToolMetadataCatalog {
  void save(ToolDescriptor descriptor);

  Optional<ToolDescriptor> find(String toolKey, int contractVersion);

  List<ToolDescriptor> findAll();
}
