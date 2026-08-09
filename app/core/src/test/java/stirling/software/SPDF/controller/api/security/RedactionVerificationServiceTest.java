package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import stirling.software.SPDF.controller.api.security.RedactionVerificationService.RedactionTarget;
import stirling.software.common.model.ApplicationProperties;

@DisplayName("RedactionVerificationService Tests")
class RedactionVerificationServiceTest {

    private final RedactionVerificationService service =
            new RedactionVerificationService(new ApplicationProperties());

    // -----------------------------------------------------------------------
    // Text verification
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Rasterizes a page where the redacted term is still extractable")
    void rasterizesPageWhereTermSurvives() throws IOException {
        try (PDDocument document = documentWithText("Account 123-456 belongs to Jane Doe")) {
            Set<Integer> rasterized =
                    service.secure(
                            document,
                            RedactionTarget.ofText(List.of("Jane Doe"), List.of(), false));

            assertEquals(Set.of(0), rasterized, "the leaking page should have been rasterized");
            assertFalse(
                    extractText(document).contains("Jane Doe"),
                    "the redacted term must no longer be extractable");
        }
    }

    @Test
    @DisplayName("Leaves a document alone when the redacted term is already gone")
    void leavesCleanDocumentUntouched() throws IOException {
        try (PDDocument document = documentWithText("Account 123-456")) {
            Set<Integer> rasterized =
                    service.secure(
                            document,
                            RedactionTarget.ofText(List.of("Jane Doe"), List.of(), false));

            assertTrue(rasterized.isEmpty(), "nothing should have been rasterized");
            assertTrue(
                    extractText(document).contains("Account 123-456"),
                    "untouched text must stay selectable");
        }
    }

    @Test
    @DisplayName("Matches a surviving term through a regex pattern")
    void detectsSurvivingRegexMatch() throws IOException {
        try (PDDocument document = documentWithText("Contact: user@example.com")) {
            Set<Integer> rasterized =
                    service.secure(
                            document,
                            RedactionTarget.ofText(
                                    List.of(), List.of("[\\w.]+@[\\w.]+\\.\\w+"), false));

            assertEquals(Set.of(0), rasterized);
            assertFalse(extractText(document).contains("user@example.com"));
        }
    }

    @Test
    @DisplayName("Whole-word mode ignores a term embedded in a longer word")
    void wholeWordModeIgnoresPartialMatch() throws IOException {
        try (PDDocument document = documentWithText("The starting line")) {
            Set<Integer> rasterized =
                    service.secure(
                            document, RedactionTarget.ofText(List.of("art"), List.of(), true));

            assertTrue(
                    rasterized.isEmpty(),
                    "'art' inside 'starting' was never a redaction target, so nothing should be"
                            + " rasterized");
        }
    }

    // -----------------------------------------------------------------------
    // Area verification
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Rasterizes a page where text survives under a redaction box")
    void rasterizesPageWithTextUnderBox() throws IOException {
        try (PDDocument document = documentWithText("Secret payload")) {
            // A generous box over the whole upper half of the page, where the text sits.
            Map<Integer, List<PDRectangle>> areas =
                    Map.of(0, List.of(new PDRectangle(0, 600, 595, 200)));

            Set<Integer> rasterized = service.secure(document, RedactionTarget.ofAreas(areas));

            assertEquals(Set.of(0), rasterized);
            assertFalse(extractText(document).contains("Secret payload"));
        }
    }

    @Test
    @DisplayName("Leaves the page alone when nothing sits under the redaction box")
    void ignoresBoxOverEmptySpace() throws IOException {
        try (PDDocument document = documentWithText("Secret payload")) {
            // Bottom-left corner, well away from the text drawn near the top of the page.
            Map<Integer, List<PDRectangle>> areas =
                    Map.of(0, List.of(new PDRectangle(0, 0, 50, 50)));

            Set<Integer> rasterized = service.secure(document, RedactionTarget.ofAreas(areas));

            assertTrue(rasterized.isEmpty());
            assertTrue(extractText(document).contains("Secret payload"));
        }
    }

    // -----------------------------------------------------------------------
    // Page wiping
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Wiping a page removes its text entirely")
    void wipePageRemovesContent() throws IOException {
        try (PDDocument document = documentWithText("Confidential")) {
            service.wipePages(document, List.of(0), Color.BLACK);

            assertFalse(extractText(document).contains("Confidential"));
        }
    }

    @Test
    @DisplayName("Ignores page indices outside the document")
    void wipeIgnoresOutOfRangePages() throws IOException {
        try (PDDocument document = documentWithText("Kept")) {
            service.wipePages(document, List.of(-1, 5), Color.BLACK);

            assertTrue(extractText(document).contains("Kept"));
        }
    }

    // -----------------------------------------------------------------------
    // Guard clauses
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Does nothing when there is nothing to verify")
    void emptyTargetIsANoOp() throws IOException {
        try (PDDocument document = documentWithText("Anything")) {
            assertTrue(service.secure(document, null).isEmpty());
            assertTrue(
                    service.secure(document, RedactionTarget.ofText(List.of(), List.of(), false))
                            .isEmpty());
            assertTrue(extractText(document).contains("Anything"));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static PDDocument documentWithText(String text) throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            contentStream.beginText();
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contentStream.newLineAtOffset(50, 700);
            contentStream.showText(text);
            contentStream.endText();
        }

        return document;
    }

    private static String extractText(PDDocument document) throws IOException {
        return new PDFTextStripper().getText(document);
    }
}
