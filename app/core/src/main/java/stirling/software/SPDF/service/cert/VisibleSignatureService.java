package stirling.software.SPDF.service.cert;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

    /**
     * The appearance layout, mirrored from the frontend composer so the signed page reproduces the
     * preview. Keep in sync with {@code
     * frontend/editor/src/core/utils/composeSignatureAppearance.ts} — {@code
     * VisibleSignatureServiceTest} pins the numbers.
     */
    private static final float CANVAS_WIDTH = 820f;

    private static final float CANVAS_HEIGHT = 250f;
    private static final float PADDING = 10f;
    private static final float GAP = 14f;
    private static final float IMAGE_COLUMN_RATIO = 0.42f;
    private static final float TEXT_INSET = 6f;
    private static final float MAX_NAME_SIZE = 48f;
    private static final float MIN_NAME_SIZE = 10f;

    /** Every line after the first is drawn at this fraction of the name's size. */
    private static final float SECONDARY_RATIO = 0.74f;

    /** Half of Helvetica's cap height: converts the canvas "middle" baseline to a PDF baseline. */
    private static final float MIDDLE_TO_BASELINE = 0.359f;

    /**
     * Resolution the appearance is stamped at. 300 DPI is the print-grade standard and keeps the
     * viewer's own downscale mild at normal zoom levels; higher only feeds the viewer pixels it
     * throws away with a filter that aliases.
     */
    private static final float STAMP_DPI = 300f;

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
            List<String> appearanceLines,
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
            // The mark always lays out in the composer's canvas, so the rectangle takes that
            // ratio whatever the user's own image happens to be shaped like.
            PDRectangle rect =
                    fitPreservingAspect(
                            toPdfRectangle(page.getMediaBox(), placement),
                            CANVAS_WIDTH,
                            CANVAS_HEIGHT);
            rectPoints =
                    new float[] {
                        rect.getLowerLeftX(),
                        rect.getLowerLeftY(),
                        rect.getWidth(),
                        rect.getHeight()
                    };
            drawAppearance(doc, page, rect, decodeAppearance(signatureImage), appearanceLines);
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
     * Shrink the placed rectangle to the appearance image's own aspect ratio, centred inside it.
     *
     * <p>The placement overlay always creates a 150x75 (2:1) box but renders the appearance with
     * CSS {@code object-fit: contain}, so on screen the mark keeps its proportions and is
     * letterboxed inside that box. Stamping with {@code drawImage(x, y, w, h)} instead stretches
     * the image to fill the rectangle, which inflated the composed appearance (820x250, 3.28:1)
     * vertically by ~64% — the signed PDF looked nothing like the preview. Fitting here reproduces
     * the same contain behaviour, and because the widget reuses this rectangle the clickable
     * signature region hugs the visible mark too.
     */
    private static PDRectangle fitPreservingAspect(
            PDRectangle rect, float imageWidth, float imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return rect;
        }
        float scale = Math.min(rect.getWidth() / imageWidth, rect.getHeight() / imageHeight);
        float width = imageWidth * scale;
        float height = imageHeight * scale;
        float x = rect.getLowerLeftX() + (rect.getWidth() - width) / 2f;
        float y = rect.getLowerLeftY() + (rect.getHeight() - height) / 2f;
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
     * Draw the signature mark: the user's image on the left, the text block on the right.
     *
     * <p>The text is drawn as REAL PDF TEXT rather than as a picture of text. A rasterised
     * appearance ends up around 12 pixels tall on screen at this size, and no amount of source
     * resolution changes that — it is resampled either way, which is what made the signed PDF look
     * blurry next to its own preview. Vector glyphs are rendered by the font engine at whatever
     * zoom the reader is at, so they stay sharp, print cleanly, and can be selected.
     */
    private void drawAppearance(
            PDDocument doc, PDPage page, PDRectangle rect, BufferedImage logo, List<String> lines)
            throws IOException {
        List<String> textLines =
                lines == null
                        ? List.of()
                        : lines.stream().filter(line -> line != null && !line.isBlank()).toList();
        boolean hasImage = logo != null;
        boolean hasText = !textLines.isEmpty();
        if (!hasImage && !hasText) {
            return;
        }

        // The rectangle carries the canvas ratio, so one uniform scale maps canvas units to points.
        float scale = Math.min(rect.getWidth() / CANVAS_WIDTH, rect.getHeight() / CANVAS_HEIGHT);
        float originX = rect.getLowerLeftX();
        float originY = rect.getLowerLeftY();

        float content = CANVAS_WIDTH - PADDING * 2 - GAP;
        float imageWidth = hasText ? content * IMAGE_COLUMN_RATIO : CANVAS_WIDTH - PADDING * 2;
        float textX = hasImage ? PADDING + imageWidth + GAP : PADDING;
        float textWidth =
                hasImage ? content * (1 - IMAGE_COLUMN_RATIO) : CANVAS_WIDTH - PADDING * 2;
        float regionHeight = CANVAS_HEIGHT - PADDING * 2;

        try (PDPageContentStream cs =
                new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

            // The composed appearance was flattened onto white; keep that so the mark stays legible
            // over whatever it lands on.
            cs.setNonStrokingColor(Color.WHITE);
            cs.addRect(originX, originY, rect.getWidth(), rect.getHeight());
            cs.fill();

            if (hasImage) {
                PDRectangle column =
                        canvasRect(
                                originX,
                                originY,
                                scale,
                                PADDING,
                                PADDING,
                                imageWidth,
                                regionHeight);
                PDRectangle fitted = fitPreservingAspect(column, logo.getWidth(), logo.getHeight());
                PDImageXObject image = buildOpaqueImage(doc, resampleForStamp(logo, fitted));
                cs.drawImage(
                        image,
                        fitted.getLowerLeftX(),
                        fitted.getLowerLeftY(),
                        fitted.getWidth(),
                        fitted.getHeight());
            }

            if (hasImage && hasText) {
                // rgba(0,0,0,0.25) over the white card.
                cs.setStrokingColor(new Color(191, 191, 191));
                cs.setLineWidth(scale);
                float dividerX = originX + (textX - GAP / 2) * scale;
                cs.moveTo(dividerX, originY + (CANVAS_HEIGHT - PADDING) * scale);
                cs.lineTo(dividerX, originY + PADDING * scale);
                cs.stroke();
            }

            if (hasText) {
                drawTextBlock(
                        cs, textLines, originX, originY, scale, textX, textWidth, regionHeight);
            }
        }
    }

    /** Map a canvas-space box (top-left origin) to a PDF rectangle. */
    private static PDRectangle canvasRect(
            float originX,
            float originY,
            float scale,
            float x,
            float y,
            float width,
            float height) {
        return new PDRectangle(
                originX + x * scale,
                originY + (CANVAS_HEIGHT - y - height) * scale,
                width * scale,
                height * scale);
    }

    private static void drawTextBlock(
            PDPageContentStream cs,
            List<String> lines,
            float originX,
            float originY,
            float scale,
            float textX,
            float textWidth,
            float regionHeight)
            throws IOException {
        PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float available = textWidth - TEXT_INSET * 2;

        // The first line is the emphasised one and sets the scale for the rest, exactly as the
        // composer does.
        String first = encodable(bold, lines.get(0));
        float nameSize = fitSize(bold, first, MAX_NAME_SIZE, available);
        float slotHeight = regionHeight / lines.size();

        cs.setNonStrokingColor(new Color(0x11, 0x11, 0x11));
        for (int i = 0; i < lines.size(); i++) {
            PDFont font = i == 0 ? bold : regular;
            String text = encodable(font, lines.get(i));
            float target = Math.round(nameSize * (i == 0 ? 1f : SECONDARY_RATIO));
            float size = Math.min(target, fitSize(font, text, target, available));

            // Canvas draws these centred on the slot ("textBaseline: middle"); PDF draws from the
            // baseline, which sits below that centre.
            float centre = PADDING + slotHeight * i + slotHeight / 2;
            float baseline = centre + MIDDLE_TO_BASELINE * size;

            cs.beginText();
            cs.setFont(font, size * scale);
            cs.newLineAtOffset(
                    originX + (textX + TEXT_INSET) * scale,
                    originY + (CANVAS_HEIGHT - baseline) * scale);
            cs.showText(text);
            cs.endText();
        }
    }

    /** Largest whole size that still fits the column, mirroring the composer's shrink loop. */
    private static float fitSize(PDFont font, String text, float maxSize, float maxWidth)
            throws IOException {
        float size = maxSize;
        while (size > MIN_NAME_SIZE && font.getStringWidth(text) / 1000f * size > maxWidth) {
            size -= 1;
        }
        return size;
    }

    /**
     * Standard 14 fonts only carry WinAnsi, so a character outside it would abort the whole
     * signature. Substitute rather than fail: a name is worth more with one odd glyph replaced than
     * not signed at all.
     */
    private static String encodable(PDFont font, String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            String character = String.valueOf(value.charAt(i));
            try {
                font.getStringWidth(character);
                safe.append(character);
            } catch (IOException | IllegalArgumentException e) {
                safe.append('?');
            }
        }
        return safe.toString();
    }

    /** Decode the composed appearance PNG; null when nothing usable was sent. */
    private static BufferedImage decodeAppearance(byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(imageBytes));
    }

    /**
     * Resample the appearance down to the resolution it will actually occupy on the page.
     *
     * <p>The frontend composes at a fixed 820x250 canvas times 3, so a ~150pt mark arrived as a
     * 2460px-wide image — around 1200 DPI. That is not extra sharpness: PDF viewers resample with a
     * cheap bilinear filter that only reads a 2x2 neighbourhood, so reducing 12:1 in one jump drops
     * most of the strokes and the text rendered blurry, however much resolution we threw at it.
     * Reducing here with a proper filter leaves the viewer a ~3:1 step it handles cleanly, and the
     * stamped image gets an order of magnitude smaller.
     */
    private static BufferedImage resampleForStamp(BufferedImage source, PDRectangle rect) {
        int targetWidth = Math.max(1, Math.round(rect.getWidth() * STAMP_DPI / 72f));
        int targetHeight = Math.max(1, Math.round(rect.getHeight() * STAMP_DPI / 72f));
        if (source.getWidth() <= targetWidth || source.getHeight() <= targetHeight) {
            // Already at or below the target — upscaling would only invent pixels.
            return source;
        }

        // Halve repeatedly first: averaging 2x2 pixels per step keeps thin strokes alive, whereas a
        // single bicubic jump reads only a 4x4 neighbourhood and aliases at these ratios.
        BufferedImage current = source;
        int width = current.getWidth();
        int height = current.getHeight();
        while (width / 2 >= targetWidth && height / 2 >= targetHeight) {
            width /= 2;
            height /= 2;
            current =
                    drawScaled(current, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (width == targetWidth && height == targetHeight) {
            return current;
        }
        return drawScaled(
                current, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage drawScaled(
            BufferedImage source, int width, int height, Object interpolation) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    /**
     * Flatten the appearance onto a white background, producing an OPAQUE DeviceRGB image (no
     * alpha/SMask) that stamps cleanly onto the page.
     */
    private static PDImageXObject buildOpaqueImage(PDDocument doc, BufferedImage appearance)
            throws IOException {
        BufferedImage opaque =
                new BufferedImage(
                        appearance.getWidth(), appearance.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = opaque.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
        g.drawImage(appearance, 0, 0, null);
        g.dispose();
        return LosslessFactory.createFromImage(doc, opaque);
    }
}
