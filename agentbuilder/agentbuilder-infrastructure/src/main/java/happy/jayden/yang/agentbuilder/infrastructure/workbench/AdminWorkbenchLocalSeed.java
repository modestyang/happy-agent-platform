package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import java.util.Objects;

public final class AdminWorkbenchLocalSeed {
  private final JdbcAdminWorkbenchStore store;

  public AdminWorkbenchLocalSeed(JdbcAdminWorkbenchStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public void seed() {
    store.seedDefaults();
  }
}
