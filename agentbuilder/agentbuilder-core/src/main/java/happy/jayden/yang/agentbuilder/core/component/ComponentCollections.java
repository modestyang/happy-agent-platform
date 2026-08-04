package happy.jayden.yang.agentbuilder.core.component;

import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

final class ComponentCollections {
  private ComponentCollections() {}

  static <T> List<T> bindings(
      List<T> values, int maximum, Function<T, String> identity, String field) {
    var copy = List.copyOf(values);
    if (copy.size() > maximum) {
      throw new IllegalArgumentException(field + " cannot contain more than " + maximum + " items");
    }
    var identities = new HashSet<String>();
    for (var value : copy) {
      if (!identities.add(identity.apply(value))) {
        throw new IllegalArgumentException(field + " contains duplicate binding identity");
      }
    }
    return copy;
  }

  static List<ConfigEntry> configEntries(List<ConfigEntry> values, String field) {
    var copy = List.copyOf(values);
    var paths = new HashSet<String>();
    for (var value : copy) {
      if (!paths.add(value.path())) {
        throw new IllegalArgumentException(
            field + " contains duplicate config path: " + value.path());
      }
    }
    return copy;
  }

  static String identity(ComponentKey key, ComponentVersion version) {
    return key.value() + "\u0000" + version.value();
  }
}
