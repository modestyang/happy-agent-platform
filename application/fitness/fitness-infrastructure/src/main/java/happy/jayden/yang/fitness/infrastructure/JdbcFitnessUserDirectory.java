package happy.jayden.yang.fitness.infrastructure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only user identity lookup owned by the Fitness schema. */
public final class JdbcFitnessUserDirectory {
  private final JdbcTemplate jdbc;

  public JdbcFitnessUserDirectory(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
  }

  public List<UUID> searchUserIds(String usernameQuery) {
    Objects.requireNonNull(usernameQuery, "usernameQuery");
    String pattern =
        "%" + usernameQuery.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    return jdbc.query(
        "SELECT user_id FROM users WHERE username IS NOT NULL"
            + " AND username ILIKE ? ESCAPE '!' ORDER BY lower(username), user_id",
        (rs, row) -> rs.getObject("user_id", UUID.class),
        pattern);
  }

  public Map<UUID, String> findUsernames(Set<UUID> userIds) {
    Objects.requireNonNull(userIds, "userIds");
    if (userIds.isEmpty()) return Map.of();
    String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
    return jdbc.query(
        "SELECT user_id, username FROM users WHERE username IS NOT NULL AND user_id IN ("
            + placeholders
            + ")",
        rs -> {
          Map<UUID, String> result = new LinkedHashMap<>();
          while (rs.next()) {
            result.put(rs.getObject("user_id", UUID.class), rs.getString("username"));
          }
          return Map.copyOf(result);
        },
        userIds.toArray());
  }
}
