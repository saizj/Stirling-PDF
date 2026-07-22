package stirling.software.SPDF.service.cert;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Calendar;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.controller.api.security.CertSignController;
import stirling.software.common.service.CustomPDFDocumentFactory;

/**
 * Blasai fork feature (Phase 2): a signature whose visible mark is a user drawn/composed image
 * placed at an arbitrary rectangle (Adobe-style), coupled with a real CLICKABLE signature field.
 *
 * <p>Two passes: (1) stamp the opaque image onto the page as ordinary content so it renders in
 * every viewer; (2) apply a VISIBLE signature at the same rectangle with an EMPTY (transparent)
 * appearance so Adobe shows a clickable signature region that opens the signature panel — while the
 * stamped image shows through underneath. An image embedded INSIDE the signature appearance
 * produced a resource tree Adobe rejects ("Se esperaba un objeto diccionario"); an empty appearance
 * keeps the field's object graph trivial. The signature covers the stamp (tamper-evident). Reuses
 * {@link CertSignController.CreateSignature} for the cryptography only.
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

        int pageIndex;
        float[] rectPoints;

        // Pass 1: stamp the opaque signature image onto the page as ordinary content.
        byte[] stamped;
        try (PDDocument doc = pdfDocumentFactory.load(pdf)) {
            pageIndex = clampPage(placement.pageIndex(), doc.getNumberOfPages());
            PDPage page = doc.getPage(pageIndex);
            PDRectangle rect = toPdfRectangle(page.getMediaBox(), placement);
            rectPoints =
                    new float[] {
                        rect.getLowerLeftX(),
                        rect.getLowerLeftY(),
                        rect.getWidth(),
                        rect.getHeight()
                    };
            PDImageXObject image = buildOpaqueImage(doc, signatureImage);
            if (image != null) {
                try (PDPageContentStream cs =
                        new PDPageContentStream(
                                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.drawImage(image, rectPoints[0], rectPoints[1], rectPoints[2], rectPoints[3]);
                }
            }
            ByteArrayOutputStream stampedOut = new ByteArrayOutputStream();
            doc.save(stampedOut);
            stamped = stampedOut.toByteArray();
        }

        // Pass 2: apply a VISIBLE signature (empty appearance) over the stamped document so the
        // region is clickable in Adobe.
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

            PDRectangle widgetRect =
                    new PDRectangle(rectPoints[0], rectPoints[1], rectPoints[2], rectPoints[3]);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (SignatureOptions options = new SignatureOptions()) {
                options.setVisualSignature(buildEmptyAppearance(doc, pageIndex, widgetRect));
                options.setPage(pageIndex);
                doc.addSignature(signature, createSignature, options);
                doc.saveIncremental(output);
            }
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
     * Build a clickable signature widget with an EMPTY (transparent, no-resource) appearance at the
     * given rectangle. Adapted from PDFBox's CreateVisibleSignature2 but with no drawn content, so
     * the field's object graph stays trivial and Adobe accepts it while the stamped image shows
     * through underneath.
     */
    private InputStream buildEmptyAppearance(PDDocument srcDoc, int pageIndex, PDRectangle rect)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(srcDoc.getPage(pageIndex).getMediaBox());
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDSignatureField signatureField = new PDSignatureField(acroForm);
            PDAnnotationWidget widget = signatureField.getWidgets().get(0);
            List<PDField> acroFormFields = acroForm.getFields();
            acroForm.setSignaturesExist(true);
            acroForm.setAppendOnly(true);
            acroForm.getCOSObject().setDirect(true);
            acroFormFields.add(signatureField);

            widget.setRectangle(rect);

            PDStream stream = new PDStream(doc);
            PDFormXObject form = new PDFormXObject(stream);
            form.setResources(new PDResources());
            form.setFormType(1);
            form.setBBox(new PDRectangle(rect.getWidth(), rect.getHeight()));

            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.getCOSObject().setDirect(true);
            PDAppearanceStream appearanceStream = new PDAppearanceStream(form.getCOSObject());
            appearance.setNormalAppearance(appearanceStream);
            widget.setAppearance(appearance);

            // Empty content stream — draw nothing so the stamp underneath shows.
            try (PDPageContentStream cs = new PDPageContentStream(doc, appearanceStream)) {
                // intentionally empty
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return new ByteArrayInputStream(baos.toByteArray());
        }
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
