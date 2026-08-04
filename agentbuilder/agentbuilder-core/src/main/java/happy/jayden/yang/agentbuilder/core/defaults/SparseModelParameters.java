package happy.jayden.yang.agentbuilder.core.defaults;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record SparseModelParameters(
    Optional<BigDecimal> temperature,
    Optional<BigDecimal> topP,
    Optional<Integer> maxOutputTokens) {
  public SparseModelParameters {
    temperature = Objects.requireNonNull(temperature, "temperature");
    topP = Objects.requireNonNull(topP, "topP");
    maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
    temperature.ifPresent(
        value -> requireRange(value, BigDecimal.ZERO, new BigDecimal("2"), "temperature"));
    topP.ifPresent(
        value -> {
          if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("topP must be greater than 0 and at most 1");
          }
        });
    maxOutputTokens.ifPresent(
        value -> {
          if (value < 1 || value > 200_000) {
            throw new IllegalArgumentException(
                "model maxOutputTokens must be between 1 and 200000");
          }
        });
  }

  public static SparseModelParameters empty() {
    return new SparseModelParameters(Optional.empty(), Optional.empty(), Optional.empty());
  }

  public SparseModelParameters withTemperature(BigDecimal value) {
    return new SparseModelParameters(Optional.of(value), topP, maxOutputTokens);
  }

  public SparseModelParameters withTopP(BigDecimal value) {
    return new SparseModelParameters(temperature, Optional.of(value), maxOutputTokens);
  }

  public SparseModelParameters withMaxOutputTokens(int value) {
    return new SparseModelParameters(temperature, topP, Optional.of(value));
  }

  SparseModelParameters withoutTemperature() {
    return new SparseModelParameters(Optional.empty(), topP, maxOutputTokens);
  }

  SparseModelParameters withoutTopP() {
    return new SparseModelParameters(temperature, Optional.empty(), maxOutputTokens);
  }

  SparseModelParameters withoutMaxOutputTokens() {
    return new SparseModelParameters(temperature, topP, Optional.empty());
  }

  private static void requireRange(
      BigDecimal value, BigDecimal minimum, BigDecimal maximum, String field) {
    Objects.requireNonNull(value, field);
    if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
  }
}
