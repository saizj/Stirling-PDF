package stirling.software.SPDF.service.cert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

    @Test
    void unusableImageDimensionsLeaveTheRectangleAlone() throws Exception {
        PDRectangle rect = new PDRectangle(5f, 5f, 120f, 60f);

        assertSame(rect, fit(rect, 0f, 250f));
        assertSame(rect, fit(rect, 820f, 0f));
    }
}
