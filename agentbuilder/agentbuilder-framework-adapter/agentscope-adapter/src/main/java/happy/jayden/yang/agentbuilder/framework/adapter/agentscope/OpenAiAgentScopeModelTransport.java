package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import reactor.core.publisher.Flux;

/** Bailian adapter through AgentScope's official OpenAI-compatible model implementation. */
final class OpenAiAgentScopeModelTransport implements AgentScopeModelTransport {
  private final OpenAIChatModel delegate;

  OpenAiAgentScopeModelTransport(RunRequest.ModelEndpoint endpoint) {
    delegate =
        endpoint
            .credential()
            .use(
                secret ->
                    OpenAIChatModel.builder()
                        .apiKey(new String(secret))
                        .modelName(endpoint.modelName())
                        .baseUrl(endpoint.baseUri().toString())
                        .stream(true)
                        .build());
  }

  @Override
  public Flux<ChatResponse> stream(
      List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    return delegate.stream(messages, tools, options);
  }

  @Override
  public String getModelName() {
    return delegate.getModelName();
  }
}
