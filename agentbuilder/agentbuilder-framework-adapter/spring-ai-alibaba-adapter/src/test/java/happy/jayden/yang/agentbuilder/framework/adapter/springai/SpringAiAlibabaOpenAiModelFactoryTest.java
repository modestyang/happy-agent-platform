package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class SpringAiAlibabaOpenAiModelFactoryTest {

  @Test
  void decomposesOpenAiCompatibleEndpointsWithoutDuplicatingV1() {
    assertEquals(
        new SpringAiAlibabaOpenAiModelFactory.OpenAiEndpoint(
            "https://dashscope.aliyuncs.com", "/compatible-mode/v1/chat/completions"),
        SpringAiAlibabaOpenAiModelFactory.endpoint(
            URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1")));
    assertEquals(
        new SpringAiAlibabaOpenAiModelFactory.OpenAiEndpoint(
            "https://api.minimaxi.com", "/v1/chat/completions"),
        SpringAiAlibabaOpenAiModelFactory.endpoint(URI.create("https://api.minimaxi.com/v1")));
    assertEquals(
        new SpringAiAlibabaOpenAiModelFactory.OpenAiEndpoint(
            "https://api.openai.com", "/v1/chat/completions"),
        SpringAiAlibabaOpenAiModelFactory.endpoint(URI.create("https://api.openai.com")));
  }

  @Test
  void rejectsUnsafeOrAmbiguousProviderEndpoints() {
    for (var value :
        java.util.List.of(
            "http://api.example.com/v1",
            "https://user@api.example.com/v1",
            "https://api.example.com/v1?token=secret",
            "https://api.example.com/v1#fragment",
            "https:///v1")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SpringAiAlibabaOpenAiModelFactory.endpoint(URI.create(value)),
          value);
    }
  }
}
