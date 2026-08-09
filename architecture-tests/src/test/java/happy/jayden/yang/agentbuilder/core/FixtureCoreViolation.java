package happy.jayden.yang.agentbuilder.core;

import happy.jayden.yang.application.FixtureApplicationDependency;

public final class FixtureCoreViolation {

  private final FixtureApplicationDependency applicationDependency =
      new FixtureApplicationDependency();

  public FixtureApplicationDependency applicationDependency() {
    return applicationDependency;
  }
}
