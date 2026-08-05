package happy.jayden.yang.agentbuilder.core.component.evaluation;

import happy.jayden.yang.agentbuilder.core.component.CatalogComponent;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record EvaluationSuiteVersion(
    ComponentMetadata metadata,
    CatalogMetadata catalogMetadata,
    List<Case> cases,
    double minimumScore,
    ScoringRule scoringRule,
    boolean safetyGate,
    List<Criterion> safetyCriteria,
    String contentChecksum)
    implements CatalogComponent {
  private static final Pattern CHECKSUM = Pattern.compile("^[a-f0-9]{64}$");

  public EvaluationSuiteVersion {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
    cases = uniqueCases(cases);
    if (Double.isNaN(minimumScore) || minimumScore < 0 || minimumScore > 1)
      throw new IllegalArgumentException("minimumScore must be between 0 and 1");
    Objects.requireNonNull(scoringRule, "scoringRule");
    if ((scoringRule == ScoringRule.SAFETY_GATE_THEN_WEIGHTED) != safetyGate)
      throw new IllegalArgumentException("scoringRule and safetyGate must agree");
    safetyCriteria = List.copyOf(Objects.requireNonNull(safetyCriteria, "safetyCriteria"));
    if (safetyGate && safetyCriteria.isEmpty())
      throw new IllegalArgumentException("safetyGate requires safetyCriteria");
    if (safetyGate && cases.stream().noneMatch(Case::safetyCase))
      throw new IllegalArgumentException("safetyGate requires a safety case");
    if (!safetyGate && (cases.stream().anyMatch(Case::safetyCase) || !safetyCriteria.isEmpty()))
      throw new IllegalArgumentException(
          "non-gated evaluations cannot contain safety cases or criteria");
    var criteria = new LinkedHashSet<String>();
    for (var criterion : safetyCriteria)
      if (!criteria.add(criterion.key()))
        throw new IllegalArgumentException("duplicate safety criterion");
    if (contentChecksum == null || !CHECKSUM.matcher(contentChecksum).matches())
      throw new IllegalArgumentException("invalid evaluation checksum");
  }

  public record Case(
      String caseKey, String input, List<Assertion> assertions, int weight, boolean safetyCase) {
    public Case {
      if (caseKey == null || !caseKey.matches("^[a-z][a-z0-9._-]{1,119}$") || input == null)
        throw new IllegalArgumentException("invalid evaluation case");
      assertions = List.copyOf(Objects.requireNonNull(assertions, "assertions"));
      if (assertions.isEmpty() || weight < 1 || weight > 100)
        throw new IllegalArgumentException("invalid evaluation case assertions or weight");
    }
  }

  public record Assertion(AssertionType type, String expected) {
    public Assertion {
      Objects.requireNonNull(type, "type");
      if (expected == null || expected.isBlank())
        throw new IllegalArgumentException("assertion expected required");
    }
  }

  public record Criterion(String key, String description) {
    public Criterion {
      if (key == null || key.isBlank() || description == null || description.isBlank())
        throw new IllegalArgumentException("invalid safety criterion");
    }
  }

  public enum ScoringRule {
    WEIGHTED_AVERAGE,
    ALL_CASES_REQUIRED,
    SAFETY_GATE_THEN_WEIGHTED
  }

  public enum AssertionType {
    EXACT,
    CONTAINS,
    JSON_PATH,
    REGEX,
    TOOL_CALL
  }

  private static List<Case> uniqueCases(List<Case> values) {
    var copy = List.copyOf(Objects.requireNonNull(values, "cases"));
    if (copy.isEmpty()) throw new IllegalArgumentException("cases must not be empty");
    var keys = new LinkedHashSet<String>();
    for (var value : copy)
      if (!keys.add(value.caseKey())) throw new IllegalArgumentException("duplicate caseKey");
    return copy;
  }
}
