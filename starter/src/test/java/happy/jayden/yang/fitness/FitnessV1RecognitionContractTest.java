package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FitnessV1RecognitionContractTest {
  @Test
  void timeoutWireFailureIsDeclaredByTheRecognitionSpecificOpenApiContract() throws Exception {
    var wire =
        FitnessV1Responses.job(
            new MealRecognitionJobDto(
                UUID.randomUUID(),
                "FAILED",
                UUID.randomUUID(),
                MealType.LUNCH,
                Instant.parse("2026-08-09T00:00:00Z"),
                List.of(),
                "TIMEOUT",
                "视觉模型调用超时",
                Instant.parse("2026-08-09T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:01Z")));

    assertThat(wire.failure().code()).isEqualTo("TIMEOUT");
    JsonNode contract =
        new ObjectMapper()
            .readTree(
                Files.readString(
                    projectRoot().resolve("docs/architecture/openapi/public-v1.yaml")));
    assertThat(
            contract.at("/components/schemas/MealRecognitionJob/properties/failure/$ref").asText())
        .isEqualTo("#/components/schemas/RecognitionFailure");
    assertThat(contract.at("/components/schemas/RecognitionFailureCode/enum"))
        .extracting(JsonNode::asText)
        .containsExactly(
            "DEPENDENCY_NOT_CONFIGURED",
            "DEPENDENCY_UNAVAILABLE",
            "TIMEOUT",
            "INVALID_MODEL_RESPONSE",
            "RUNTIME_ERROR");
  }

  private static Path projectRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      if (Files.isRegularFile(directory.resolve("docs/architecture/openapi/public-v1.yaml"))) {
        return directory;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Unable to locate the repository root");
  }
}
