package happy.jayden.yang.agentbuilder.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolErrorResponseTest {

  @Test
  void serializesOnlyTheBoundedRetryableInvalidArgumentContract() throws Exception {
    var error =
        new ToolInputException(
            "无效参数\n" + "x".repeat(500), new IllegalArgumentException("internal cause"));
    var response = ToolErrorResponse.invalidArgument(error);
    var json = response.json();
    var document = new ObjectMapper().readTree(json);
    var names = new HashSet<String>();
    document.fieldNames().forEachRemaining(names::add);

    assertEquals(Set.of("ok", "code", "message", "retryable"), names);
    assertFalse(document.get("ok").asBoolean());
    assertEquals("INVALID_ARGUMENT", document.get("code").asText());
    assertTrue(document.get("retryable").asBoolean());
    assertTrue(document.get("message").asText().length() <= 240);
    assertFalse(document.get("message").asText().contains("\n"));
    assertFalse(json.contains("internal cause"));
    assertFalse(json.contains("stack"));
  }
}
