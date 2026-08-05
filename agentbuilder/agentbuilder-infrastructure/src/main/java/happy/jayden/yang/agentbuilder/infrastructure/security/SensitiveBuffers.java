package happy.jayden.yang.agentbuilder.infrastructure.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class SensitiveBuffers {
  private SensitiveBuffers() {}

  static byte[] encodeAndClear(char[] source) {
    ByteBuffer encoded = null;
    try {
      encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(source));
      return copyAndClear(encoded);
    } finally {
      Arrays.fill(source, '\0');
      if (encoded != null) clear(encoded);
    }
  }

  static byte[] copyAndClear(ByteBuffer source) {
    try {
      var copy = new byte[source.remaining()];
      source.get(copy);
      return copy;
    } finally {
      clear(source);
    }
  }

  static char[] copyAndClear(CharBuffer source) {
    try {
      var copy = new char[source.remaining()];
      source.get(copy);
      return copy;
    } finally {
      clear(source);
    }
  }

  static void clear(ByteBuffer buffer) {
    if (buffer.hasArray()) {
      Arrays.fill(buffer.array(), (byte) 0);
      return;
    }
    for (int index = 0; index < buffer.capacity(); index++) buffer.put(index, (byte) 0);
  }

  static void clear(CharBuffer buffer) {
    if (buffer.hasArray()) {
      Arrays.fill(buffer.array(), '\0');
      return;
    }
    for (int index = 0; index < buffer.capacity(); index++) buffer.put(index, '\0');
  }
}
