package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** Creates Spring AI's actual OpenAI-compatible transport for a published model endpoint. */
final class SpringAiAlibabaOpenAiModelFactory {
  private SpringAiAlibabaOpenAiModelFactory() {}

  static OpenAiChatModel create(RunRequest.ModelEndpoint endpoint) {
    return endpoint
        .credential()
        .use(
            secret ->
                OpenAiChatModel.builder()
                    .openAiApi(
                        OpenAiApi.builder()
                            .baseUrl(endpoint.baseUri().toString())
                            .apiKey(new String(secret))
                            .build())
                    .defaultOptions(OpenAiChatOptions.builder().model(endpoint.modelName()).build())
                    .build());
  }
}
