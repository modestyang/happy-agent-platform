package happy.jayden.yang.agentbuilder.core.component.provider;

/** Closed non-secret fields safe for administrative serialization. */
public record ProviderPublicConfig(
    String region, String apiVersion, String organization, String project) {
  public ProviderPublicConfig {
    valid(region);
    valid(apiVersion);
    valid(organization);
    valid(project);
  }

  public static ProviderPublicConfig empty() {
    return new ProviderPublicConfig(null, null, null, null);
  }

  private static void valid(String value) {
    if (value != null && value.isBlank())
      throw new IllegalArgumentException("public config must not be blank");
  }
}
