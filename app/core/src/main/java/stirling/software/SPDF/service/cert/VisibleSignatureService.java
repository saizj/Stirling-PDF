package stirling.software.SPDF.service.cert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Calendar;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
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
 * Blasai fork feature (Phase 2): applies a cryptographic PDF signature whose VISIBLE appearance is
 * a user-drawn image placed at an arbitrary rectangle on the page (Adobe-style). Reuses {@link
 * CertSignController.CreateSignature} for the cryptography only; the appearance is built here so
 * the upstream fixed 200x50 logo box is left untouched.
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

        CertSignController.CreateSignature createSignature =
                new CertSignController.CreateSignature(keyStore, password);

        try (PDDocument doc = pdfDocumentFactory.load(pdf)) {
            int pageIndex = clampPage(placement.pageIndex(), doc.getNumberOfPages());
            PDRectangle rect = toPdfRectangle(doc.getPage(pageIndex).getMediaBox(), placement);

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(name);
            signature.setLocation(location);
            signature.setReason(reason);
            signature.setSignDate(Calendar.getInstance());

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (SignatureOptions options = new SignatureOptions()) {
                options.setVisualSignature(buildAppearance(doc, pageIndex, rect, signatureImage));
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
     * Build the signature widget appearance (a positioned box that draws the supplied PNG). Adapted
     * from PDFBox's CreateVisibleSignature2 example but parametrised for position and image.
     */
    private InputStream buildAppearance(
            PDDocument srcDoc, int pageIndex, PDRectangle rect, byte[] imageBytes)
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
            PDResources res = new PDResources();
            form.setResources(res);
            form.setFormType(1);
            PDRectangle bbox = new PDRectangle(rect.getWidth(), rect.getHeight());
            form.setBBox(bbox);

            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.getCOSObject().setDirect(true);
            PDAppearanceStream appearanceStream = new PDAppearanceStream(form.getCOSObject());
            appearance.setNormalAppearance(appearanceStream);
            widget.setAppearance(appearance);

            try (PDPageContentStream cs = new PDPageContentStream(doc, appearanceStream)) {
                if (imageBytes != null && imageBytes.length > 0) {
                    PDImageXObject image =
                            PDImageXObject.createFromByteArray(doc, imageBytes, "signature");
                    cs.drawImage(image, 0, 0, rect.getWidth(), rect.getHeight());
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return new ByteArrayInputStream(baos.toByteArray());
        }
    }
}
