package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

final class CatalogJsonCodec {
  private final ObjectMapper mapper;

  CatalogJsonCodec(ObjectMapper mapper) {
    this.mapper =
        mapper
            .copy()
            .deactivateDefaultTyping()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new ConfigValueJacksonModule());
  }

  static CatalogJsonCodec standard() {
    return new CatalogJsonCodec(new ObjectMapper());
  }

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("catalog payload cannot be serialized", exception);
    }
  }

  <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("stored catalog payload is invalid", exception);
    }
  }
}
