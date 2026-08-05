package happy.jayden.yang.agentbuilder.service.catalog;

import java.util.List;
import java.util.Objects;

public record ValidationReport(List<String> errors) {
  public ValidationReport {
    errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
  }

  public boolean hasErrors() {
    return !errors.isEmpty();
  }

  public boolean isValid() {
    return errors.isEmpty();
  }
}
