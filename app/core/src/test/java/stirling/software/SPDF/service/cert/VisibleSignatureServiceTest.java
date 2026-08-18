package stirling.software.SPDF.service.cert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The signed page has to reproduce what the placement overlay showed. The overlay always creates a
 * 150x75 (2:1) box and renders the appearance with {@code object-fit: contain}, so stamping the
 * image stretched to that box inflated it vertically and the signature looked nothing like the
 * preview.
 */
class VisibleSignatureServiceTest {

    private static final float EPSILON = 0.01f;

    private static PDRectangle fit(PDRectangle rect, float imageWidth, float imageHeight)
            throws Exception {
        Method m =
                VisibleSignatureService.class.getDeclaredMethod(
                        "fitPreservingAspect", PDRectangle.class, float.class, float.class);
        m.setAccessible(true);
        return (PDRectangle) m.invoke(null, rect, imageWidth, imageHeight);
    }

    @Test
    void wideAppearanceKeepsItsRatioAndIsCentredVertically() throws Exception {
        // The real case: the 820x250 composed appearance in the overlay's 2:1 box.
        PDRectangle fitted = fit(new PDRectangle(100f, 200f, 150f, 75f), 820f, 250f);

        assertEquals(150f, fitted.getWidth(), EPSILON, "should use the full box width");
        assertEquals(45.73f, fitted.getHeight(), EPSILON, "height follows the image ratio");
        assertEquals(100f, fitted.getLowerLeftX(), EPSILON);
        assertEquals(214.63f, fitted.getLowerLeftY(), EPSILON, "centred in the box");
        assertEquals(
                820f / 250f,
                fitted.getWidth() / fitted.getHeight(),
                EPSILON,
                "aspect ratio preserved");
    }

    @Test
    void tallAppearanceIsLimitedByHeightAndCentredHorizontally() throws Exception {
        PDRectangle fitted = fit(new PDRectangle(0f, 0f, 200f, 100f), 100f, 200f);

        assertEquals(50f, fitted.getWidth(), EPSILON);
        assertEquals(100f, fitted.getHeight(), EPSILON, "should use the full box height");
        assertEquals(75f, fitted.getLowerLeftX(), EPSILON, "centred in the box");
        assertEquals(0f, fitted.getLowerLeftY(), EPSILON);
    }

    @Test
    void matchingRatioFillsTheBox() throws Exception {
        PDRectangle fitted = fit(new PDRectangle(10f, 20f, 300f, 150f), 820f, 410f);

        assertEquals(300f, fitted.getWidth(), EPSILON);
        assertEquals(150f, fitted.getHeight(), EPSILON);
        assertEquals(10f, fitted.getLowerLeftX(), EPSILON);
        assertEquals(20f, fitted.getLowerLeftY(), EPSILON);
    }

    private static BufferedImage resample(BufferedImage source, PDRectangle rect) throws Exception {
        Method m =
                VisibleSignatureService.class.getDeclaredMethod(
                        "resampleForStamp", BufferedImage.class, PDRectangle.class);
        m.setAccessible(true);
        return (BufferedImage) m.invoke(null, source, rect);
    }

    @Test
    void appearanceIsBroughtDownToStampResolution() throws Exception {
        // The composer's 820x250 canvas at 3x, stamped at the usual ~149pt width.
        BufferedImage source = new BufferedImage(2460, 750, BufferedImage.TYPE_INT_ARGB);
        BufferedImage resampled = resample(source, new PDRectangle(0f, 0f, 149f, 45.4f));

        // 149pt at 300 DPI.
        assertEquals(621, resampled.getWidth());
        assertEquals(189, resampled.getHeight());
    }

    @Test
    void anAppearanceBelowStampResolutionIsLeftUntouched() throws Exception {
        BufferedImage source = new BufferedImage(300, 92, BufferedImage.TYPE_INT_ARGB);

        assertSame(source, resample(source, new PDRectangle(0f, 0f, 149f, 45.4f)));
    }

    private static String appearanceText(List<String> lines, BufferedImage logo) throws Exception {
        Method draw =
                VisibleSignatureService.class.getDeclaredMethod(
                        "drawAppearance",
                        PDDocument.class,
                        PDPage.class,
                        PDRectangle.class,
                        BufferedImage.class,
                        List.class);
        draw.setAccessible(true);

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            draw.invoke(
                    new VisibleSignatureService(null),
                    doc,
                    page,
                    new PDRectangle(60f, 600f, 149f, 45.4f),
                    logo,
                    lines);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            try (PDDocument reopened = Loader.loadPDF(out.toByteArray())) {
                return new PDFTextStripper().getText(reopened);
            }
        }
    }

    @Test
    void theAppearanceIsRealTextRatherThanAPictureOfText() throws Exception {
        // The whole point of the vector appearance: a reader can extract these, which a stamped
        // bitmap could never offer — and they stay sharp at any zoom for the same reason.
        String extracted =
                appearanceText(List.of("BLASAI Software", "50478386X", "18/08/2026 09:48"), null);

        assertTrue(extracted.contains("BLASAI Software"), extracted);
        assertTrue(extracted.contains("50478386X"), extracted);
        assertTrue(extracted.contains("18/08/2026 09:48"), extracted);
    }

    @Test
    void blankLinesAreDropped() throws Exception {
        String extracted = appearanceText(Arrays.asList("Name", "  ", null, "Date"), null);

        assertTrue(extracted.contains("Name"), extracted);
        assertTrue(extracted.contains("Date"), extracted);
    }

    @Test
    void charactersTheFontCannotEncodeDoNotAbortTheSignature() throws Exception {
        // A standard-14 font is WinAnsi only; an emoji used to blow up the whole request.
        String extracted = appearanceText(List.of("Jes\u00fas \ud83d\ude80 Saiz"), null);

        assertTrue(extracted.contains("Jes"), extracted);
        assertTrue(extracted.contains("Saiz"), extracted);
    }

    @Test
    void unusableImageDimensionsLeaveTheRectangleAlone() throws Exception {
        PDRectangle rect = new PDRectangle(5f, 5f, 120f, 60f);

        assertSame(rect, fit(rect, 0f, 250f));
        assertSame(rect, fit(rect, 820f, 0f));
    }
}
