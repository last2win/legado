import java.io.FileInputStream;
import java.util.Properties;
import java.security.KeyStore;
class CheckSigningProperties {
    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (var input = new FileInputStream(args[0])) { props.load(input); }
        if (!System.getenv("RS_ALIAS").equals(props.getProperty("RELEASE_KEY_ALIAS")) ||
            !System.getenv("RS_STORE_PASS").equals(props.getProperty("RELEASE_STORE_PASSWORD")) ||
            !System.getenv("RS_KEY_PASS").equals(props.getProperty("RELEASE_KEY_PASSWORD"))) {
            throw new AssertionError("Gradle properties changed a credential value");
        }
        KeyStore store = KeyStore.getInstance(props.getProperty("RELEASE_STORE_TYPE"));
        try (var input = new FileInputStream(args[1])) { store.load(input, props.getProperty("RELEASE_STORE_PASSWORD").toCharArray()); }
        if (store.getKey(props.getProperty("RELEASE_KEY_ALIAS"), props.getProperty("RELEASE_KEY_PASSWORD").toCharArray()) == null)
            throw new AssertionError("Private key missing");
    }
}
