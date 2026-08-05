package happy.jayden.yang.agentbuilder.core.component;

/** Common catalog contract without coupling domain definitions to a framework. */
public interface CatalogComponent {
  ComponentMetadata metadata();

  CatalogMetadata catalogMetadata();
}
