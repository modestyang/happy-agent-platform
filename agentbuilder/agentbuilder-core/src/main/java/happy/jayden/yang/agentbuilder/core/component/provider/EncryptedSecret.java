package happy.jayden.yang.agentbuilder.core.component.provider;

import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import java.util.Arrays;
import java.util.Objects;

public final class EncryptedSecret {
  private final ComponentRef component;
  private final byte[] ciphertext;
  private final byte[] iv;

  public EncryptedSecret(ComponentRef component, byte[] ciphertext, byte[] iv) {
    this.component = Objects.requireNonNull(component, "component");
    this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
    this.iv = Arrays.copyOf(iv, iv.length);
    if (ciphertext.length < 16 || iv.length != 12)
      throw new IllegalArgumentException("invalid AES-GCM payload");
  }

  public ComponentRef component() {
    return component;
  }

  public byte[] ciphertext() {
    return Arrays.copyOf(ciphertext, ciphertext.length);
  }

  public byte[] iv() {
    return Arrays.copyOf(iv, iv.length);
  }

  @Override
  public String toString() {
    return "EncryptedSecret[component=" + component + ", ciphertext=****, iv=****]";
  }
}
