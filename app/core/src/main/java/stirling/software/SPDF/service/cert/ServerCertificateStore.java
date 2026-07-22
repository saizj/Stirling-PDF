package stirling.software.SPDF.service.cert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.configuration.InstallationPathConfig;
import stirling.software.common.service.ServerCertificateServiceInterface;

/**
 * Blasai fork feature: stores multiple signing certificates on the server (in the /configs volume)
 * so PDFs can be signed by picking a stored certificate, without re-uploading the .p12 or re-typing
 * the password on every signature.
 *
 * <p>Each certificate is persisted as {@code <id>.p12} (the original keystore bytes) plus {@code
 * <id>.properties} (display name, cached subject/issuer/validity metadata and the keystore password
 * encrypted at rest with AES-GCM). The AES key lives in {@code .store-key} inside the same
 * directory (owner-only permissions where the filesystem supports it).
 *
 * <p>This bean also implements {@link ServerCertificateServiceInterface} so the existing {@code
 * certType=SERVER} path and the {@code serverCertificateEnabled} config flag light up, using the
 * first stored certificate as the default. It is only present in the open (core) build; the
 * proprietary implementation is never compiled into that flavor, so there is no bean conflict.
 */
@Slf4j
@Service
public class ServerCertificateStore implements ServerCertificateServiceInterface {

    private static final String DIR_NAME = "server-certificates";
    private static final String KEY_FILE = ".store-key";
    private static final String DEFAULT_FILE = ".default";
    private static final String GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Metadata for a stored certificate, safe to expose over the API (no secrets). */
    public record CertEntry(
            String id,
            String name,
            String subject,
            String issuer,
            Date validFrom,
            Date validTo,
            boolean isDefault) {}

    /** A loaded keystore together with the password that unlocks its private key. */
    public record ResolvedKeyStore(KeyStore keyStore, char[] password) {}

    private Path baseDir() throws IOException {
        Path dir = Paths.get(InstallationPathConfig.getConfigPath(), DIR_NAME);
        Files.createDirectories(dir);
        return dir;
    }

    // ---- Public multi-certificate API (used by the controller) ----

    public synchronized List<CertEntry> listCertificates() throws IOException {
        Path dir = baseDir();
        List<CertEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        String defaultId = readDefaultId(dir);
        try (var stream = Files.newDirectoryStream(dir, "*.properties")) {
            for (Path props : stream) {
                String id = stripExtension(props.getFileName().toString());
                try {
                    entries.add(readEntry(id, loadProperties(props), id.equals(defaultId)));
                } catch (Exception ex) {
                    log.warn("Skipping unreadable server certificate metadata: {}", id, ex);
                }
            }
        }
        entries.sort(Comparator.comparing(e -> e.name() == null ? "" : e.name().toLowerCase()));
        // If no explicit default is set, the first (alphabetical) entry is the implicit default.
        if ((defaultId == null || entries.stream().noneMatch(CertEntry::isDefault))
                && !entries.isEmpty()) {
            CertEntry first = entries.get(0);
            entries.set(
                    0,
                    new CertEntry(
                            first.id(),
                            first.name(),
                            first.subject(),
                            first.issuer(),
                            first.validFrom(),
                            first.validTo(),
                            true));
        }
        return entries;
    }

    public synchronized CertEntry addCertificate(String name, byte[] p12Bytes, String password)
            throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(p12Bytes), password.toCharArray());

        String keyAlias = firstKeyAlias(keyStore);
        if (keyAlias == null) {
            throw new IllegalArgumentException("The keystore does not contain a private key entry");
        }
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(keyAlias);

        String id = UUID.randomUUID().toString();
        Path dir = baseDir();
        Files.write(dir.resolve(id + ".p12"), p12Bytes);

        Properties props = new Properties();
        props.setProperty("name", name == null || name.isBlank() ? keyAlias : name.trim());
        props.setProperty("password", encrypt(password));
        if (cert != null) {
            props.setProperty("subject", cert.getSubjectX500Principal().getName());
            props.setProperty("issuer", cert.getIssuerX500Principal().getName());
            props.setProperty("validFrom", Long.toString(cert.getNotBefore().getTime()));
            props.setProperty("validTo", Long.toString(cert.getNotAfter().getTime()));
        }
        storeProperties(dir.resolve(id + ".properties"), props);

        log.info("Stored server certificate '{}' (id={})", props.getProperty("name"), id);
        return listCertificates().stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElse(readEntry(id, props, false));
    }

    public synchronized void deleteCertificate(String id) throws IOException {
        String safeId = requireSafeId(id);
        Path dir = baseDir();
        Files.deleteIfExists(dir.resolve(safeId + ".p12"));
        Files.deleteIfExists(dir.resolve(safeId + ".properties"));
        if (safeId.equals(readDefaultId(dir))) {
            Files.deleteIfExists(dir.resolve(DEFAULT_FILE));
        }
        log.info("Deleted server certificate id={}", safeId);
    }

    public synchronized ResolvedKeyStore resolve(String id) throws Exception {
        String safeId = requireSafeId(id);
        Path dir = baseDir();
        Path p12 = dir.resolve(safeId + ".p12");
        Path propsFile = dir.resolve(safeId + ".properties");
        if (!Files.exists(p12) || !Files.exists(propsFile)) {
            throw new IllegalArgumentException("No stored certificate with id " + safeId);
        }
        String password = decrypt(loadProperties(propsFile).getProperty("password"));
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(Files.readAllBytes(p12)), password.toCharArray());
        return new ResolvedKeyStore(keyStore, password.toCharArray());
    }

    // ---- ServerCertificateServiceInterface (default = first stored certificate) ----

    @Override
    public boolean isEnabled() {
        // The "Server" signing mode should only surface once at least one certificate exists.
        return hasServerCertificate();
    }

    @Override
    public boolean hasServerCertificate() {
        try {
            return !listCertificates().isEmpty();
        } catch (IOException ex) {
            log.warn("Failed to list server certificates", ex);
            return false;
        }
    }

    @Override
    public void initializeServerCertificate() {
        try {
            baseDir();
        } catch (IOException ex) {
            log.warn("Failed to initialise server certificate store", ex);
        }
    }

    @Override
    public KeyStore getServerKeyStore() throws Exception {
        return resolve(requireDefaultId()).keyStore();
    }

    @Override
    public String getServerCertificatePassword() {
        try {
            return new String(resolve(requireDefaultId()).password());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read server certificate password", ex);
        }
    }

    @Override
    public X509Certificate getServerCertificate() throws Exception {
        KeyStore keyStore = getServerKeyStore();
        String alias = firstKeyAlias(keyStore);
        return alias == null ? null : (X509Certificate) keyStore.getCertificate(alias);
    }

    @Override
    public byte[] getServerCertificatePublicKey() throws Exception {
        X509Certificate cert = getServerCertificate();
        return cert == null ? null : cert.getPublicKey().getEncoded();
    }

    @Override
    public void uploadServerCertificate(InputStream p12Stream, String password) throws Exception {
        addCertificate("Server certificate", p12Stream.readAllBytes(), password);
    }

    @Override
    public void deleteServerCertificate() throws Exception {
        Optional<String> id = defaultId();
        if (id.isPresent()) {
            deleteCertificate(id.get());
        }
    }

    @Override
    public ServerCertificateInfo getServerCertificateInfo() throws Exception {
        Optional<String> id = defaultId();
        if (id.isEmpty()) {
            return new ServerCertificateInfo(false, null, null, null, null);
        }
        CertEntry entry =
                readEntry(
                        id.get(),
                        loadProperties(baseDir().resolve(id.get() + ".properties")),
                        true);
        return new ServerCertificateInfo(
                true, entry.subject(), entry.issuer(), entry.validFrom(), entry.validTo());
    }

    // ---- Helpers ----

    private Optional<String> defaultId() throws IOException {
        return listCertificates().stream()
                .filter(CertEntry::isDefault)
                .map(CertEntry::id)
                .findFirst();
    }

    private String requireDefaultId() throws IOException {
        return defaultId()
                .orElseThrow(() -> new IllegalStateException("No server certificate configured"));
    }

    private CertEntry readEntry(String id, Properties props, boolean isDefault) {
        Date from = parseDate(props.getProperty("validFrom"));
        Date to = parseDate(props.getProperty("validTo"));
        return new CertEntry(
                id,
                props.getProperty("name"),
                props.getProperty("subject"),
                props.getProperty("issuer"),
                from,
                to,
                isDefault);
    }

    private String readDefaultId(Path dir) {
        try {
            Path marker = dir.resolve(DEFAULT_FILE);
            if (Files.exists(marker)) {
                String id = Files.readString(marker, StandardCharsets.UTF_8).trim();
                return id.isEmpty() ? null : id;
            }
        } catch (IOException ex) {
            log.warn("Failed to read default certificate marker", ex);
        }
        return null;
    }

    public synchronized void setDefault(String id) throws IOException {
        String safeId = requireSafeId(id);
        Path dir = baseDir();
        if (!Files.exists(dir.resolve(safeId + ".properties"))) {
            throw new IllegalArgumentException("No stored certificate with id " + safeId);
        }
        Files.writeString(dir.resolve(DEFAULT_FILE), safeId, StandardCharsets.UTF_8);
        log.info("Set active server certificate id={}", safeId);
    }

    private static Date parseDate(String millis) {
        if (millis == null || millis.isBlank()) {
            return null;
        }
        try {
            return new Date(Long.parseLong(millis));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstKeyAlias(KeyStore keyStore) throws Exception {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                return alias;
            }
        }
        return null;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String requireSafeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid certificate id");
        }
        return id;
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static void storeProperties(Path path, Properties props) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            props.store(writer, "Blasai server certificate metadata");
        }
    }

    // ---- Encryption at rest (AES-GCM with a locally generated key) ----

    private synchronized SecretKeySpec loadOrCreateKey() throws IOException {
        Path keyPath = baseDir().resolve(KEY_FILE);
        byte[] keyBytes;
        if (Files.exists(keyPath)) {
            keyBytes = Files.readAllBytes(keyPath);
        } else {
            keyBytes = new byte[AES_KEY_BYTES];
            secureRandom.nextBytes(keyBytes);
            Files.write(keyPath, keyBytes);
            restrictPermissions(keyPath);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ex) {
            // Non-POSIX filesystem (e.g. Windows dev box); best-effort only.
            log.debug("Could not restrict permissions on {}", path);
        }
    }

    private String encrypt(String plain) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String encoded) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encoded);
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }
}
