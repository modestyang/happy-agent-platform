package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import io.agentscope.core.model.Model;

/** Internal model seam; deterministic implementations are confined to test sources. */
interface AgentScopeModelTransport extends Model, AutoCloseable {
  default void interrupt() {}

  @Override
  default void close() {}
}
