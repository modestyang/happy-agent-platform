package happy.jayden.yang.agentbuilder.core.component.provider;

public interface CredentialCipher {
  EncryptedSecret encrypt(char[] plaintext);

  char[] decrypt(EncryptedSecret encrypted);
}
