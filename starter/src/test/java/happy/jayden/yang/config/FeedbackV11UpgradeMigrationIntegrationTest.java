package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FeedbackV11UpgradeMigrationIntegrationTest {

  private static final String SCHEMA = "fitness_upgrade";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.14-alpine3.24")
          .withDatabaseName("happy_agent")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void v11NormalizesV10UnicodeWhitespaceRowsBeforeAddingTheStrictCheck() {
    flyway("10").migrate();
    JdbcTemplate jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    UUID userId = UUID.randomUUID();
    UUID nonOtherRecommendationId = UUID.randomUUID();
    UUID otherRecommendationId = UUID.randomUUID();
    UUID rejectedRecommendationId = UUID.randomUUID();

    jdbc.update(
        "INSERT INTO fitness_upgrade.users(user_id,external_subject,status) VALUES (?,?, 'ACTIVE')",
        userId,
        "upgrade-user");
    recommendation(jdbc, userId, nonOtherRecommendationId, LocalDate.of(2026, 10, 1));
    recommendation(jdbc, userId, otherRecommendationId, LocalDate.of(2026, 10, 2));
    recommendation(jdbc, userId, rejectedRecommendationId, LocalDate.of(2026, 10, 3));
    jdbc.update(
        "INSERT INTO fitness_upgrade.meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','TASTE',?)",
        userId,
        nonOtherRecommendationId,
        "\u2003");
    jdbc.update(
        "INSERT INTO fitness_upgrade.meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER',?)",
        userId,
        otherRecommendationId,
        "\u00a0");

    assertThatCode(() -> flyway(null).migrate()).doesNotThrowAnyException();

    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM fitness_upgrade.meal_recommendation_feedback WHERE recommendation_id=?",
                String.class,
                nonOtherRecommendationId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM fitness_upgrade.meal_recommendation_feedback WHERE recommendation_id=?",
                String.class,
                otherRecommendationId))
        .isEqualTo("历史反馈未提供有效说明");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO fitness_upgrade.meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER',?)",
                    userId,
                    rejectedRecommendationId,
                    "\u00a0"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static Flyway flyway(String target) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .table("fitness_schema_history")
            .locations("classpath:db/fitness")
            .createSchemas(true);
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private static void recommendation(
      JdbcTemplate jdbc, UUID userId, UUID recommendationId, LocalDate date) {
    jdbc.update(
        "INSERT INTO fitness_upgrade.daily_meal_recommendations(recommendation_id,user_id,recommendation_date,meal_type,items,reason,status) VALUES (?,?,?,'BREAKFAST','[]'::jsonb,'upgrade test','READY')",
        recommendationId,
        userId,
        date);
  }
}
