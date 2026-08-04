package happy.jayden.yang.agentbuilder.core.tool;

import java.util.Objects;

public final class ToolText {

  private ToolText() {}

  public static String require(
      String value, int minimumCodePoints, int maximumCodePoints, String field) {
    return require(value, minimumCodePoints, maximumCodePoints, field, false);
  }

  public static String require(
      String value,
      int minimumCodePoints,
      int maximumCodePoints,
      String field,
      boolean allowBlank) {
    Objects.requireNonNull(value, field);
    requireValidUnicode(value, field);
    var length = value.codePointCount(0, value.length());
    if ((!allowBlank && value.isBlank())
        || length < minimumCodePoints
        || length > maximumCodePoints) {
      throw new IllegalArgumentException(
          field
              + " length must be between "
              + minimumCodePoints
              + " and "
              + maximumCodePoints
              + " code points");
    }
    return value;
  }

  public static void requireValidUnicode(String value, String field) {
    Objects.requireNonNull(value, field);
    for (int index = 0; index < value.length(); index++) {
      var current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(field + " contains an unpaired Unicode surrogate");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException(field + " contains an unpaired Unicode surrogate");
      }
    }
  }
}
