import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;

/** Validate existing release identity without exposing credentials or creating/replacing keys. */
class ValidateAndroidSigning {
    public static void main(String[] args) {
        char[] storePassword = System.getenv("RS_STORE_PASS").toCharArray();
        char[] keyPassword = System.getenv("RS_KEY_PASS").toCharArray();
        String stage = "Keystore could not be opened: check RELEASE_KEY_STORE format/completeness and RELEASE_STORE_PASSWORD";
        try {
            KeyStore store = KeyStore.getInstance(new File(args[0]), storePassword);
            String alias = System.getenv("RS_ALIAS");
            stage = "RELEASE_KEY_ALIAS does not identify a private-key entry in this keystore";
            if (!store.isKeyEntry(alias)) throw new IllegalArgumentException();
            stage = "Private key could not be decrypted: check RELEASE_KEY_PASSWORD";
            var key = store.getKey(alias, keyPassword);
            if (!(key instanceof PrivateKey)) throw new IllegalArgumentException();
            stage = "Signing key and certificate are missing, mismatched or unsupported";
            var certificate = store.getCertificate(alias);
            if (certificate == null) throw new IllegalArgumentException();
            String algorithm = switch (key.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                case "DSA" -> "SHA256withDSA";
                default -> throw new IllegalArgumentException();
            };
            byte[] challenge = "legado-signing-preflight".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign((PrivateKey) key);
            signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(certificate.getPublicKey());
            signature.update(challenge);
            if (!signature.verify(signed)) throw new IllegalArgumentException();
            System.out.println(store.getType());
        } catch (Exception failure) {
            // Provider messages can include aliases or other credential material. Do not print them.
            System.err.println(stage);
            System.exit(1);
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }
}
