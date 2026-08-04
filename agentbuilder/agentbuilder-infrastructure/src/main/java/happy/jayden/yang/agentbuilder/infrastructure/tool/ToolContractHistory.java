package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

final class ToolContractHistory {

  private final Map<String, NavigableMap<Integer, ToolDescriptor>> versions;

  ToolContractHistory(Collection<ToolDescriptor> completeMetadata) {
    Objects.requireNonNull(completeMetadata, "completeHistoricalMetadata");
    var grouped = new HashMap<String, NavigableMap<Integer, ToolDescriptor>>();
    for (var descriptor : completeMetadata) {
      Objects.requireNonNull(descriptor, "completeHistoricalMetadata item");
      var toolVersions = grouped.computeIfAbsent(descriptor.toolKey(), ignored -> new TreeMap<>());
      if (toolVersions.putIfAbsent(descriptor.contractVersion(), descriptor) != null) {
        throw new IllegalArgumentException(
            "historical metadata contains duplicate Tool version " + identity(descriptor));
      }
    }
    var immutable = new HashMap<String, NavigableMap<Integer, ToolDescriptor>>();
    grouped.forEach(
        (toolKey, toolVersions) -> {
          var expected = 1;
          for (var version : toolVersions.keySet()) {
            if (version != expected) {
              throw new IllegalArgumentException(
                  "historical metadata for "
                      + toolKey
                      + " must be complete from version 1; missing version "
                      + expected);
            }
            expected++;
          }
          immutable.put(toolKey, Collections.unmodifiableNavigableMap(new TreeMap<>(toolVersions)));
        });
    versions = Map.copyOf(immutable);
  }

  void validate(ToolDescriptor descriptor) {
    var history = versions.get(descriptor.toolKey());
    if (history == null) {
      if (descriptor.contractVersion() != 1) {
        throw new IllegalStateException(
            "new Tool contract must start at version 1: " + identity(descriptor));
      }
      return;
    }
    var maximum = history.lastKey();
    if (descriptor.contractVersion() < maximum) {
      throw new IllegalStateException(
          "Tool contract "
              + identity(descriptor)
              + " is below historical maximum version "
              + maximum);
    }
    var historical = history.get(descriptor.contractVersion());
    if (historical != null && !historical.schemaChecksum().equals(descriptor.schemaChecksum())) {
      throw new IllegalStateException(
          "Tool contract "
              + identity(descriptor)
              + " changed checksum; increment contractVersion before deployment");
    }
  }

  private static String identity(ToolDescriptor descriptor) {
    return descriptor.toolKey() + "@" + descriptor.contractVersion();
  }
}
