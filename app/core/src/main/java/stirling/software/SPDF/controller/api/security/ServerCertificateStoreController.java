package stirling.software.SPDF.controller.api.security;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.service.cert.ServerCertificateStore;
import stirling.software.SPDF.service.cert.ServerCertificateStore.CertEntry;
import stirling.software.SPDF.service.cert.ServerCertificateStore.ResolvedKeyStore;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.WebResponseUtils;

/**
 * Blasai fork feature: manage signing certificates stored on the server and sign PDFs by picking a
 * stored certificate (no per-signature password). Runs on the self-hosted instance with security
 * disabled, so these endpoints are intentionally open on that deployment.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/certificates")
@Tag(name = "Server Certificates", description = "Manage server-stored signing certificates")
public class ServerCertificateStoreController {

    private final ServerCertificateStore store;
    private final CustomPDFDocumentFactory pdfDocumentFactory;

    @GetMapping
    @Operation(
            summary = "List stored server certificates",
            description =
                    "Returns metadata (no secrets) for every certificate stored on the server")
    public List<CertEntry> list() throws Exception {
        return store.listCertificates();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Add a server certificate",
            description = "Uploads a PKCS12/PFX keystore and its password to store on the server")
    public CertEntry add(
            @RequestParam("file") MultipartFile file,
            @RequestParam("password") String password,
            @RequestParam(value = "name", required = false) String name)
            throws Exception {
        return store.addCertificate(name, file.getBytes(), password);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a stored server certificate")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws Exception {
        store.deleteCertificate(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    @Operation(
            summary = "Set the active server certificate",
            description = "Marks the certificate used by the \"Server\" signing mode")
    public ResponseEntity<Void> setDefault(@PathVariable("id") String id) throws Exception {
        store.setDefault(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Sign a PDF with a stored server certificate",
            description = "Signs the PDF using the chosen stored certificate without a password")
    public ResponseEntity<byte[]> sign(
            @RequestParam("fileInput") MultipartFile fileInput,
            @RequestParam("certId") String certId,
            @RequestParam(value = "showSignature", defaultValue = "false") Boolean showSignature,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "pageNumber", required = false) Integer pageNumber,
            @RequestParam(value = "showLogo", defaultValue = "true") Boolean showLogo)
            throws Exception {

        ResolvedKeyStore resolved = store.resolve(certId);
        CertSignController.CreateSignature createSignature =
                new CertSignController.CreateSignature(resolved.keyStore(), resolved.password());

        // sign() expects a 0-indexed page; the API takes 1-indexed (default page 1).
        int pageIndex = (pageNumber != null && pageNumber > 0) ? pageNumber - 1 : 0;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CertSignController.sign(
                pdfDocumentFactory,
                fileInput,
                output,
                createSignature,
                showSignature,
                pageIndex,
                name,
                location,
                reason,
                showLogo);

        return WebResponseUtils.bytesToWebResponse(output.toByteArray(), "signed.pdf");
    }
}
