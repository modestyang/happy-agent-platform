package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public final class TextValidation {
  private TextValidation() {}

  public static String requireUnicodeScalar(String value, String field) {
    Objects.requireNonNull(value, field);
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(field + " must contain only Unicode scalar values");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException(field + " must contain only Unicode scalar values");
      }
    }
    return value;
  }

  public static String requireLength(String value, int minimum, int maximum, String field) {
    requireUnicodeScalar(value, field);
    int length = value.codePointCount(0, value.length());
    if (length < minimum || length > maximum) {
      throw new IllegalArgumentException(
          field + " must contain " + minimum + " to " + maximum + " characters");
    }
    return value;
  }

  public static String requireNonBlankLength(String value, int minimum, int maximum, String field) {
    requireLength(value, minimum, maximum, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " cannot be blank");
    }
    return value;
  }
}
