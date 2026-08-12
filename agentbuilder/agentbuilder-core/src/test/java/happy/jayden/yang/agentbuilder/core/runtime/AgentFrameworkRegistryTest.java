package happy.jayden.yang.agentbuilder.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentFrameworkRegistryTest {

  @Test
  void resolvesAnAdapterByItsStableKey() {
    var adapter = new TestAdapter("agentscope");

    assertEquals(
        adapter, new AgentFrameworkRegistry(java.util.List.of(adapter)).required("agentscope"));
  }

  @Test
  void rejectsDuplicateOrUnknownKeys() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentFrameworkRegistry(
                java.util.List.of(new TestAdapter("saa"), new TestAdapter("saa"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentFrameworkRegistry(java.util.List.of(new TestAdapter("saa")))
                .required("missing"));
  }

  private record TestAdapter(String key) implements AgentFrameworkAdapter {
    @Override
    public FrameworkCapabilities capabilities() {
      return new FrameworkCapabilities(false, false, false, false, false, false);
    }

    @Override
    public void validate(ResolvedAgentConfig resolvedAgentConfig) {}

    @Override
    public Flux<RunEvent> run(RunRequest request) {
      return Flux.empty();
    }
  }
}
