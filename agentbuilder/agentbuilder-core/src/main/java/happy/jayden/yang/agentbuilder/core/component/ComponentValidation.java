package happy.jayden.yang.agentbuilder.core.component;

import java.util.regex.Pattern;

final class ComponentValidation {
  static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9._-]{1,159}$");
  static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

  private ComponentValidation() {}

  static String requireKey(String value) {
    TextValidation.requireUnicodeScalar(value, "componentKey");
    if (!KEY.matcher(value).matches()) {
      throw new IllegalArgumentException("componentKey must satisfy " + KEY.pattern());
    }
    return value;
  }

  static String requireChecksum(String value) {
    TextValidation.requireUnicodeScalar(value, "componentChecksum");
    if (!SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException("componentChecksum must be a lowercase SHA-256 value");
    }
    return value;
  }
}
