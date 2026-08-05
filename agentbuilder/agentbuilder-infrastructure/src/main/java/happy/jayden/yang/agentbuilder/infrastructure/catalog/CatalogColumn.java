package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import java.util.List;
import java.util.function.Function;

record CatalogColumn<T>(String name, String cast, Function<T, Object> value, Encoding encoding) {
  static <T> CatalogColumn<T> raw(String name, Function<T, Object> value) {
    return new CatalogColumn<>(name, "", value, Encoding.RAW);
  }

  static <T> CatalogColumn<T> json(String name, Function<T, Object> value) {
    return new CatalogColumn<>(name, "::jsonb", value, Encoding.JSON);
  }

  static <T> CatalogColumn<T> textArray(String name, Function<T, List<String>> value) {
    return new CatalogColumn<>(name, "::text[]", value::apply, Encoding.TEXT_ARRAY);
  }

  Object argument(T aggregate, CatalogJsonCodec codec) {
    var extracted = value.apply(aggregate);
    return switch (encoding) {
      case RAW -> extracted;
      case JSON -> codec.write(extracted);
      case TEXT_ARRAY -> AbstractJdbcCatalogRepository.postgresArray(castStrings(extracted));
    };
  }

  @SuppressWarnings("unchecked")
  private static List<String> castStrings(Object value) {
    return (List<String>) value;
  }

  enum Encoding {
    RAW,
    JSON,
    TEXT_ARRAY
  }
}
