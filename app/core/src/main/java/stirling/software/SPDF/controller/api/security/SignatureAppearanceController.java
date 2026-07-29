package stirling.software.SPDF.controller.api.security;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.cert.SignatureAppearanceStore;
import stirling.software.SPDF.service.cert.SignatureAppearanceStore.SignatureAppearance;

/**
 * Blasai fork feature: server-side storage for the saved signature appearances, so the same
 * appearances show up on every device instead of only in the browser that created them.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/signature-appearances")
@Tag(name = "Signature Appearances", description = "Manage server-stored signature appearances")
public class SignatureAppearanceController {

    private final SignatureAppearanceStore store;

    @GetMapping
    @Operation(summary = "List saved signature appearances")
    public List<SignatureAppearance> list() throws Exception {
        return store.list();
    }

    @PostMapping
    @Operation(summary = "Save a new signature appearance")
    public SignatureAppearance create(@RequestBody SignatureAppearance appearance)
            throws Exception {
        return store.save(
                new SignatureAppearance(
                        null,
                        appearance.name(),
                        appearance.signatureData(),
                        appearance.signatureType(),
                        appearance.includeImage(),
                        appearance.includeName(),
                        appearance.includeId(),
                        appearance.includeDate()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing signature appearance")
    public SignatureAppearance update(
            @PathVariable("id") String id, @RequestBody SignatureAppearance appearance)
            throws Exception {
        return store.save(
                new SignatureAppearance(
                        id,
                        appearance.name(),
                        appearance.signatureData(),
                        appearance.signatureType(),
                        appearance.includeImage(),
                        appearance.includeName(),
                        appearance.includeId(),
                        appearance.includeDate()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a saved signature appearance")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws Exception {
        store.delete(id);
        return ResponseEntity.noContent().build();
    }
}
