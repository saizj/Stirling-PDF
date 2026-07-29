package stirling.software.SPDF.service.cert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.configuration.InstallationPathConfig;

import tools.jackson.databind.ObjectMapper;

/**
 * Blasai fork feature: stores the Adobe-style signature appearances ("aspectos") on the server
 * instead of in each browser's localStorage, so the same appearances are available from every
 * device.
 *
 * <p>Each appearance is one {@code <id>.json} file inside {@code <config>/signature-appearances/},
 * which lives on the mounted /configs volume and therefore survives redeploys.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureAppearanceStore {

    private static final String DIR_NAME = "signature-appearances";

    private final ObjectMapper objectMapper;

    /** A saved signature appearance. {@code signatureData} is a base64 data URL of the drawing. */
    public record SignatureAppearance(
            String id,
            String name,
            String signatureData,
            String signatureType,
            boolean includeImage,
            boolean includeName,
            boolean includeId,
            boolean includeDate) {}

    private Path baseDir() throws IOException {
        Path dir = Paths.get(InstallationPathConfig.getConfigPath(), DIR_NAME);
        Files.createDirectories(dir);
        return dir;
    }

    public synchronized List<SignatureAppearance> list() throws IOException {
        Path dir = baseDir();
        List<SignatureAppearance> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    entries.add(objectMapper.readValue(json, SignatureAppearance.class));
                } catch (RuntimeException | IOException ex) {
                    log.warn(
                            "Skipping unreadable signature appearance: {}", file.getFileName(), ex);
                }
            }
        }
        entries.sort(Comparator.comparing(e -> e.name() == null ? "" : e.name().toLowerCase()));
        return entries;
    }

    /** Creates the appearance when {@code id} is blank, otherwise overwrites the existing one. */
    public synchronized SignatureAppearance save(SignatureAppearance appearance)
            throws IOException {
        String id =
                (appearance.id() == null || appearance.id().isBlank())
                        ? UUID.randomUUID().toString()
                        : requireSafeId(appearance.id());
        String name =
                (appearance.name() == null || appearance.name().isBlank())
                        ? "Sin nombre"
                        : appearance.name().trim();
        SignatureAppearance stored =
                new SignatureAppearance(
                        id,
                        name,
                        appearance.signatureData(),
                        appearance.signatureType(),
                        appearance.includeImage(),
                        appearance.includeName(),
                        appearance.includeId(),
                        appearance.includeDate());
        Files.writeString(
                baseDir().resolve(id + ".json"),
                objectMapper.writeValueAsString(stored),
                StandardCharsets.UTF_8);
        log.info("Stored signature appearance '{}' (id={})", name, id);
        return stored;
    }

    public synchronized void delete(String id) throws IOException {
        String safeId = requireSafeId(id);
        Files.deleteIfExists(baseDir().resolve(safeId + ".json"));
        log.info("Deleted signature appearance id={}", safeId);
    }

    private static String requireSafeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid signature appearance id");
        }
        return id;
    }
}
