package happy.jayden.yang.agentbuilder.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Framework-neutral parity contract. Adapter test suites supply evidence gathered from their real
 * framework execution boundary; this fixture owns the required observable outcomes.
 */
public abstract class FrameworkAdapterContract {
  protected abstract ConformanceEvidence conformanceEvidence();

  @Test
  final void declaresTheCompleteNeutralCapabilitySet() {
    var capabilities = conformanceEvidence().capabilities();

    assertTrue(capabilities.tools());
    assertTrue(capabilities.skills());
    assertTrue(capabilities.hooks());
    assertTrue(capabilities.memory());
    assertTrue(capabilities.streaming());
    assertTrue(capabilities.cancellation());
  }

  @Test
  final void preservesStrictSchemasSkillsHooksAndNeutralStreamOrdering() {
    var evidence = conformanceEvidence();

    assertEquals(Boolean.FALSE, evidence.strictSchema().get("additionalProperties"));
    assertTrue(evidence.skillPrompt().contains("fitness"));
    assertEquals(
        RunEvent.Type.RUN_COMPLETED,
        evidence.skillEvents().get(evidence.skillEvents().size() - 1).type());
    if (!evidence.skillToolName().isBlank()) {
      assertEquals(
          List.of(RunEvent.Type.TOOL_STARTED, RunEvent.Type.TOOL_RESULT),
          evidence.skillEvents().stream()
              .filter(
                  event -> evidence.skillToolName().equals(event.data().get("toolName")))
              .map(RunEvent::type)
              .toList());
    }
    assertEquals(
        List.of(
            "pre-agent",
            "pre-model",
            "post-model",
            "pre-tool",
            "post-tool",
            "pre-model",
            "post-model",
            "post-agent"),
        evidence.mandatoryHookOrder());
    assertInRelativeOrder(
        evidence.events(),
        List.of(
            RunEvent.Type.RUN_STARTED,
            RunEvent.Type.MODEL_DELTA,
            RunEvent.Type.TOOL_STARTED,
            RunEvent.Type.TOOL_RESULT,
            RunEvent.Type.MODEL_DELTA,
            RunEvent.Type.RUN_COMPLETED));
    assertTrue(isStrictlyIncreasing(evidence.events()));
    assertTrue(
        Set.of("userId", "runId", "permissions", "operationId").stream()
            .noneMatch(evidence.modelArguments()::containsKey));
    assertEquals("user-1", evidence.observedToolContext().userId());
    assertEquals("run-1", evidence.observedToolContext().runId());
    assertEquals(Set.of("fitness:read"), evidence.observedToolContext().grantedScopes());
    assertEquals("operation-1", evidence.observedToolContext().operationId());
  }

  @Test
  final void normalizesStructuredOutputCancellationAndFailures() {
    var evidence = conformanceEvidence();

    assertEquals(RunEvent.Type.RUN_FAILED, evidence.failures().get(RunFailureCode.TOOL).type());
    assertEquals(
        RunFailureCode.TOOL,
        result(evidence.failures().get(RunFailureCode.TOOL)).failure().orElseThrow().code());
    assertEquals(RunEvent.Type.RUN_FAILED, evidence.failures().get(RunFailureCode.HOOK).type());
    assertEquals(
        RunFailureCode.HOOK,
        result(evidence.failures().get(RunFailureCode.HOOK)).failure().orElseThrow().code());
    assertEquals(1, evidence.cancellationSignals());
  }

  /** A compact report emitted by each adapter's contract suite for CI diagnostics. */
  protected final Map<String, Object> report() {
    var evidence = conformanceEvidence();
    return Map.of(
        "capabilities", evidence.capabilities(),
        "events", evidence.events().stream().map(RunEvent::type).toList(),
        "skillEvents", evidence.skillEvents().size(),
        "cancellationSignals", evidence.cancellationSignals());
  }

  private static RunResult result(RunEvent event) {
    var result = event.data().get("result");
    assertNotNull(result);
    assertTrue(result instanceof RunResult);
    return (RunResult) result;
  }

  private static boolean isStrictlyIncreasing(List<RunEvent> events) {
    for (var index = 1; index < events.size(); index++) {
      if (events.get(index - 1).sequence() >= events.get(index).sequence()) {
        return false;
      }
    }
    return !events.isEmpty();
  }

  private static void assertInRelativeOrder(List<RunEvent> events, List<RunEvent.Type> expected) {
    int nextExpected = 0;
    for (var event : events) {
      if (nextExpected < expected.size() && event.type() == expected.get(nextExpected)) {
        nextExpected++;
      }
    }
    assertEquals(expected.size(), nextExpected, "legacy lifecycle must remain ordered among typed events");
  }

  protected record ConformanceEvidence(
      FrameworkCapabilities capabilities,
      Map<String, Object> strictSchema,
      String skillPrompt,
      String skillToolName,
      List<RunEvent> skillEvents,
      List<String> mandatoryHookOrder,
      List<RunEvent> events,
      Map<String, Object> modelArguments,
      ToolExecutionContext observedToolContext,
      Map<RunFailureCode, RunEvent> failures,
      int cancellationSignals) {
    public ConformanceEvidence {
      strictSchema = Map.copyOf(strictSchema);
      skillEvents = List.copyOf(skillEvents);
      mandatoryHookOrder = List.copyOf(mandatoryHookOrder);
      events = List.copyOf(events);
      modelArguments = Map.copyOf(modelArguments);
      failures = Map.copyOf(failures);
    }
  }
}
