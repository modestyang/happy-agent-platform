package happy.jayden.yang.agentbuilder.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import org.junit.jupiter.api.Test;

class SensitiveBuffersTest {
  @Test
  void copyingEncodedBytesClearsTheEntireBackingArray() {
    var backing = new byte[] {9, 1, 2, 3, 9};
    var buffer = ByteBuffer.wrap(backing, 1, 3).slice();
    assertArrayEquals(new byte[] {1, 2, 3}, SensitiveBuffers.copyAndClear(buffer));
    assertArrayEquals(new byte[5], backing);
  }

  @Test
  void copyingDecodedCharactersClearsTheEntireBackingArray() {
    var backing = new char[] {'x', 's', 'e', 'c', 'x'};
    var buffer = CharBuffer.wrap(backing, 1, 3).slice();
    assertArrayEquals(new char[] {'s', 'e', 'c'}, SensitiveBuffers.copyAndClear(buffer));
    assertArrayEquals(new char[5], backing);
  }
}
