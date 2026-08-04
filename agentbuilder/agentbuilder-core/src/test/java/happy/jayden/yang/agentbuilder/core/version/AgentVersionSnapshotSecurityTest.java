package happy.jayden.yang.agentbuilder.core.version;

import static happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentDefinitionTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.defaults.AgentOverrides;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationDefaults;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class AgentVersionSnapshotSecurityTest {

  @Test
  void snapshotCanOnlyBePublishedOrRehydratedFromOneResolvedDefinition() throws Exception {
    assertTrue(Modifier.isFinal(AgentVersionSnapshot.class.getModifiers()));
    assertTrue(
        java.util.Arrays.stream(AgentVersionSnapshot.class.getDeclaredConstructors())
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));

    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.base");
    var application =
        new ApplicationDefaults(
            "fitness", baseline.defaultProfileVersion().publishedRef(), DefaultValues.empty());
    var definition =
        resolver()
            .resolveDefinition(
                limits(), codeDefaults(), application, AgentOverrides.none(), baseline);
    var snapshot = AgentVersionSnapshot.publish(definition);

    new ObjectMapper().readTree(snapshot.canonicalJson());
    assertEquals(
        snapshot,
        AgentVersionSnapshot.rehydrate(definition, snapshot.canonicalJson(), snapshot.checksum()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentVersionSnapshot.rehydrate(
                definition, snapshot.canonicalJson() + " ", snapshot.checksum()));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentVersionSnapshot.rehydrate(definition, snapshot.canonicalJson(), "A".repeat(64)));
  }

  @Test
  void checksumUsesReportingUtf8AndKnownSha256Vector() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        SnapshotChecksum.sha256("abc"));
    assertThrows(IllegalArgumentException.class, () -> SnapshotChecksum.sha256("x\ud800y"));
    assertThrows(IllegalArgumentException.class, () -> SnapshotChecksum.sha256("x\udc00y"));
  }

  @Test
  void canonicalNumbersAndEveryComponentMutationAffectChecksum() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.base");
    var application =
        new ApplicationDefaults(
            "fitness", baseline.defaultProfileVersion().publishedRef(), DefaultValues.empty());
    var definition =
        resolver()
            .resolveDefinition(
                limits(), codeDefaults(), application, AgentOverrides.none(), baseline);
    var snapshot = AgentVersionSnapshot.publish(definition);
    assertTrue(snapshot.canonicalJson().contains("\"temperature\":0.2"));
    assertTrue(snapshot.canonicalJson().contains("\"maxCostUsd\":1"));

    var changed = components("memory.changed", "schema.base", "evaluation.base", "defaults.base");
    var changedDefinition =
        resolver()
            .resolveDefinition(
                limits(), codeDefaults(), application, AgentOverrides.none(), changed);
    assertNotEquals(
        snapshot.checksum(), AgentVersionSnapshot.publish(changedDefinition).checksum());
  }
}
