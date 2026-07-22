package stirling.software.SPDF.service.cert;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Calendar;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.controller.api.security.CertSignController;
import stirling.software.common.service.CustomPDFDocumentFactory;

/**
 * Blasai fork feature (Phase 2): applies a signature whose visible mark is a user-drawn/composed
 * image placed at an arbitrary rectangle (Adobe-style), then covers it with an INVISIBLE
 * cryptographic signature.
 *
 * <p>Two passes: (1) stamp the opaque image onto the page as ordinary content; (2) apply an
 * invisible signature over the stamped document. Embedding the image as an in-field signature
 * appearance (PDFBox {@code setVisualSignature}) produced a resource tree Adobe Acrobat rejects
 * ("Se esperaba un objeto diccionario"); an invisible signature over stamped content validates
 * cleanly in Adobe while remaining visually identical and tamper-evident (the signature covers the
 * stamp). Reuses {@link CertSignController.CreateSignature} for the cryptography only.
 *
 * <p>Placement coordinates arrive as fractions (0..1) of the page with a top-left origin (matching
 * the frontend overlay); they are converted to PDFBox's bottom-left point space here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisibleSignatureService {

    private final CustomPDFDocumentFactory pdfDocumentFactory;

    /** A signature rectangle expressed as fractions (0..1) of the page, top-left origin. */
    public record Placement(
            int pageIndex,
            float xFraction,
            float yFraction,
            float widthFraction,
            float heightFraction) {}

    public byte[] sign(
            MultipartFile pdf,
            KeyStore keyStore,
            char[] password,
            Placement placement,
            byte[] signatureImage,
            String name,
            String location,
            String reason)
            throws Exception {

        // Pass 1: stamp the opaque signature image onto the page as ordinary content.
        byte[] stamped;
        try (PDDocument doc = pdfDocumentFactory.load(pdf)) {
            int pageIndex = clampPage(placement.pageIndex(), doc.getNumberOfPages());
            PDPage page = doc.getPage(pageIndex);
            PDRectangle rect = toPdfRectangle(page.getMediaBox(), placement);
            PDImageXObject image = buildOpaqueImage(doc, signatureImage);
            if (image != null) {
                try (PDPageContentStream cs =
                        new PDPageContentStream(
                                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.drawImage(
                            image,
                            rect.getLowerLeftX(),
                            rect.getLowerLeftY(),
                            rect.getWidth(),
                            rect.getHeight());
                }
            }
            ByteArrayOutputStream stampedOut = new ByteArrayOutputStream();
            doc.save(stampedOut);
            stamped = stampedOut.toByteArray();
        }

        // Pass 2: apply an invisible cryptographic signature over the stamped document.
        CertSignController.CreateSignature createSignature =
                new CertSignController.CreateSignature(keyStore, password);
        try (PDDocument doc = pdfDocumentFactory.load(stamped)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(name);
            signature.setLocation(location);
            signature.setReason(reason);
            signature.setSignDate(Calendar.getInstance());

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            doc.addSignature(signature, createSignature);
            doc.saveIncremental(output);
            return output.toByteArray();
        }
    }

    private static int clampPage(int pageIndex, int pageCount) {
        if (pageIndex < 0) return 0;
        if (pageIndex >= pageCount) return pageCount - 1;
        return pageIndex;
    }

    /** Convert a top-left fractional placement into a bottom-left PDF-point rectangle. */
    private static PDRectangle toPdfRectangle(PDRectangle mediaBox, Placement p) {
        float pageWidth = mediaBox.getWidth();
        float pageHeight = mediaBox.getHeight();
        float width = p.widthFraction() * pageWidth;
        float height = p.heightFraction() * pageHeight;
        float x = mediaBox.getLowerLeftX() + p.xFraction() * pageWidth;
        float y = mediaBox.getLowerLeftY() + pageHeight - (p.yFraction() * pageHeight) - height;
        return new PDRectangle(x, y, width, height);
    }

    /**
     * Decode the signature PNG and flatten it onto a white background, producing an OPAQUE
     * DeviceRGB image (no alpha/SMask) that stamps cleanly onto the page.
     */
    private static PDImageXObject buildOpaqueImage(PDDocument doc, byte[] imageBytes)
            throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (decoded == null) {
            return null;
        }
        BufferedImage opaque =
                new BufferedImage(
                        decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = opaque.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
        g.drawImage(decoded, 0, 0, null);
        g.dispose();
        return LosslessFactory.createFromImage(doc, opaque);
    }
}
