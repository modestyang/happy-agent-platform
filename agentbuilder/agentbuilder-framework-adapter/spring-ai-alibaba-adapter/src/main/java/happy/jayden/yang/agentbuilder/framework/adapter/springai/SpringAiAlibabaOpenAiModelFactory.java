package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** Creates Spring AI's actual OpenAI-compatible transport for a published model endpoint. */
final class SpringAiAlibabaOpenAiModelFactory {
  private SpringAiAlibabaOpenAiModelFactory() {}

  static OpenAiChatModel create(RunRequest.ModelEndpoint endpoint) {
    var resolvedEndpoint = endpoint(endpoint.baseUri());
    return endpoint
        .credential()
        .use(
            secret ->
                OpenAiChatModel.builder()
                    .openAiApi(
                        OpenAiApi.builder()
                            .baseUrl(resolvedEndpoint.baseUrl())
                            .completionsPath(resolvedEndpoint.completionsPath())
                            .apiKey(new String(secret))
                            .build())
                    .defaultOptions(OpenAiChatOptions.builder().model(endpoint.modelName()).build())
                    .build());
  }

  static OpenAiEndpoint endpoint(URI endpoint) {
    if (endpoint == null
        || endpoint.getScheme() == null
        || !"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getHost().isBlank()
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) {
      throw new IllegalArgumentException(
          "model endpoint must be an HTTPS origin without credentials, query or fragment");
    }
    String baseUrl;
    try {
      baseUrl =
          new URI("https", null, endpoint.getHost(), endpoint.getPort(), null, null, null)
              .toString();
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("model endpoint origin is invalid", error);
    }
    var path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath();
    while (path.endsWith("/") && path.length() > 1) {
      path = path.substring(0, path.length() - 1);
    }
    var completionsPath =
        path.endsWith("/v1") ? path + "/chat/completions" : "/v1/chat/completions";
    return new OpenAiEndpoint(baseUrl, completionsPath);
  }

  record OpenAiEndpoint(String baseUrl, String completionsPath) {}
}
