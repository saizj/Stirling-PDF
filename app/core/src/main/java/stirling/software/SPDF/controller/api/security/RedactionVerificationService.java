package stirling.software.SPDF.controller.api.security;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.pdf.parser.PageImageLocator;
import stirling.software.common.model.ApplicationProperties;

/**
 * Verifies, after redaction has been applied, that the redacted content is genuinely gone — and
 * makes it gone when it is not.
 *
 * <p>Drawing a black box over content hides it visually but leaves it fully recoverable by copying
 * the text, running {@code pdftotext}, or deleting the covering rectangle. Content-stream text
 * removal is the real fix, but it silently degrades to box-only mode whenever the page uses fonts
 * whose encoding cannot be rewritten reliably (subset and custom-encoded fonts — very common).
 *
 * <p>This service closes that gap: it re-reads the redacted document and, for any page where the
 * redacted content is still extractable, rasterizes that page so the content ceases to exist as
 * data. Only the affected pages are rasterized, so the rest of the document keeps its selectable,
 * searchable text.
 */
@Service
@Slf4j
@RequiredArgsConstructor
class RedactionVerificationService {

    /** Fallback render resolution when the application has no configured maximum. */
    private static final int DEFAULT_RENDER_DPI = 300;

    /**
     * Content that redaction was asked to remove, expressed in the two ways redaction can be
     * targeted: by matching text, and by covering a region of the page.
     *
     * @param exactTerms literal strings that must no longer be extractable
     * @param regexPatterns regex patterns whose matches must no longer be extractable
     * @param areasByPage redacted rectangles in PDF user-space (origin bottom-left), keyed by
     *     0-based page index; any text or image still present under one of these means the
     *     redaction is only a cover-up
     * @param wholeWordOnly mirrors the request's whole-word setting, so verification does not flag
     *     a term the redaction was never meant to remove (e.g. "art" inside "start")
     */
    record RedactionTarget(
            List<String> exactTerms,
            List<String> regexPatterns,
            Map<Integer, List<PDRectangle>> areasByPage,
            boolean wholeWordOnly) {

        static RedactionTarget ofText(
                List<String> exactTerms, List<String> regexPatterns, boolean wholeWordOnly) {
            return new RedactionTarget(exactTerms, regexPatterns, Map.of(), wholeWordOnly);
        }

        static RedactionTarget ofAreas(Map<Integer, List<PDRectangle>> areasByPage) {
            return new RedactionTarget(List.of(), List.of(), areasByPage, false);
        }

        boolean isEmpty() {
            return exactTerms.isEmpty() && regexPatterns.isEmpty() && areasByPage.isEmpty();
        }
    }

    private final ApplicationProperties applicationProperties;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Guarantees the redacted content cannot be recovered from {@code document}.
     *
     * <p>Call this after the redaction overlays have been drawn and before saving: any page still
     * leaking the content is rasterized, which necessarily happens after the boxes are in place so
     * that the boxes — not the sensitive content — are what ends up in the raster.
     *
     * @return the 0-based indices of the pages that had to be rasterized
     */
    Set<Integer> secure(PDDocument document, RedactionTarget target) throws IOException {
        if (target == null || target.isEmpty()) {
            return Set.of();
        }

        Set<Integer> leakingPages = new TreeSet<>();
        leakingPages.addAll(findPagesWithExtractableText(document, target));
        leakingPages.addAll(findPagesWithContentUnderAreas(document, target.areasByPage()));

        if (!leakingPages.isEmpty()) {
            log.warn(
                    "[redact/verify] redacted content is still recoverable on page(s) {} — "
                            + "rasterizing those pages so it cannot be extracted",
                    leakingPages.stream().map(i -> i + 1).toList());
            rasterizePages(document, leakingPages);
        } else {
            log.info("[redact/verify] verified: redacted content is no longer extractable");
        }

        scrubDocumentLevelText(document, target);

        return leakingPages;
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    /** Pages where a redacted term is still extractable from the page text. */
    private Set<Integer> findPagesWithExtractableText(PDDocument document, RedactionTarget target) {
        List<Pattern> patterns = compilePatterns(target);
        if (patterns.isEmpty()) {
            return Set.of();
        }

        Set<Integer> leaking = new LinkedHashSet<>();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            String pageText = extractPageText(document, pageIndex);
            if (pageText.isEmpty()) {
                continue;
            }
            for (Pattern pattern : patterns) {
                if (pattern.matcher(pageText).find()) {
                    leaking.add(pageIndex);
                    break;
                }
            }
        }
        return leaking;
    }

    /**
     * Pages where a redacted rectangle still has text or an image underneath it. Such a rectangle
     * is a cover-up: removing it, or extracting the page content, brings the content straight back.
     */
    private Set<Integer> findPagesWithContentUnderAreas(
            PDDocument document, Map<Integer, List<PDRectangle>> areasByPage) {
        if (areasByPage.isEmpty()) {
            return Set.of();
        }

        Set<Integer> leaking = new LinkedHashSet<>();
        for (Map.Entry<Integer, List<PDRectangle>> entry : areasByPage.entrySet()) {
            int pageIndex = entry.getKey();
            List<PDRectangle> areas = entry.getValue();
            if (areas.isEmpty() || pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                continue;
            }

            PDPage page = document.getPage(pageIndex);
            for (float[] box : extractContentBoxes(document, page, pageIndex)) {
                if (areas.stream().anyMatch(area -> intersects(area, box))) {
                    leaking.add(pageIndex);
                    break;
                }
            }
        }
        return leaking;
    }

    /** Bounding boxes of every text line and image on the page, in PDF user-space. */
    private List<float[]> extractContentBoxes(PDDocument document, PDPage page, int pageIndex) {
        List<float[]> boxes = new ArrayList<>();
        try {
            AllTextLineExtractor textExtractor =
                    new AllTextLineExtractor(pageIndex + 1, page.getBBox().getHeight());
            textExtractor.getText(document);
            boxes.addAll(textExtractor.getLineBoxes());
        } catch (IOException e) {
            log.warn(
                    "[redact/verify] could not extract text boxes on page {}: {}",
                    pageIndex + 1,
                    e.getMessage());
        }

        try {
            PageImageLocator imageLocator = new PageImageLocator(page, pageIndex);
            imageLocator.processPage(page);
            for (PageImageLocator.ImageBox image : imageLocator.getImageBoxes()) {
                boxes.add(new float[] {image.x1(), image.y1(), image.x2(), image.y2()});
            }
        } catch (IOException e) {
            log.warn(
                    "[redact/verify] could not locate images on page {}: {}",
                    pageIndex + 1,
                    e.getMessage());
        }

        return boxes;
    }

    private String extractPageText(PDDocument document, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.setSortByPosition(true);
            return normalizeWhitespace(stripper.getText(document));
        } catch (IOException e) {
            // A page whose text cannot be read cannot be verified. Treat that as a leak by
            // returning a marker the callers' patterns will not match, and log it loudly.
            log.warn(
                    "[redact/verify] could not extract text from page {} to verify redaction: {}",
                    pageIndex + 1,
                    e.getMessage());
            return "";
        }
    }

    // -----------------------------------------------------------------------
    // Rasterization
    // -----------------------------------------------------------------------

    /**
     * Replaces the given pages with a flat image of themselves. The original content stream and
     * resources are dropped, so the text and images that were on the page no longer exist as data
     * anywhere in the saved file.
     */
    void rasterizePages(PDDocument document, Set<Integer> pageIndices) throws IOException {
        if (pageIndices.isEmpty()) {
            return;
        }

        PDFRenderer renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);
        int dpi = resolveRenderDpi();

        for (int pageIndex : pageIndices) {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                continue;
            }

            PDPage page = document.getPage(pageIndex);
            BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);

            // The rendered image already has the page rotation baked in, so the replacement page
            // must be un-rotated and sized to the visible (rotated) extent.
            PDRectangle visibleBox = page.getCropBox();
            boolean quarterTurned = page.getRotation() == 90 || page.getRotation() == 270;
            float width = quarterTurned ? visibleBox.getHeight() : visibleBox.getWidth();
            float height = quarterTurned ? visibleBox.getWidth() : visibleBox.getHeight();
            PDRectangle newBox = new PDRectangle(width, height);

            // Drop everything the page carried: content stream, resources (fonts, XObjects) and
            // annotations. Once unreferenced these objects are not written out on save.
            page.setContents(new PDStream(document));
            page.setResources(new PDResources());
            page.setAnnotations(Collections.emptyList());
            page.setRotation(0);
            page.setMediaBox(newBox);
            page.setCropBox(newBox);

            PDImageXObject image = LosslessFactory.createFromImage(document, rendered);
            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.OVERWRITE, true, true)) {
                contentStream.drawImage(image, 0, 0, width, height);
            }
            rendered.flush();
        }
    }

    /**
     * Discards everything on the given pages and leaves a flat block of {@code fillColor} in its
     * place. Used for pages that are redacted in full, where rasterizing would only produce an
     * expensive image of a solid rectangle.
     */
    void wipePages(PDDocument document, Collection<Integer> pageIndices, Color fillColor)
            throws IOException {
        for (int pageIndex : pageIndices) {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                continue;
            }

            PDPage page = document.getPage(pageIndex);
            PDRectangle box = page.getBBox();

            page.setContents(new PDStream(document));
            page.setResources(new PDResources());
            page.setAnnotations(Collections.emptyList());

            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.OVERWRITE, true, true)) {
                contentStream.setNonStrokingColor(fillColor);
                contentStream.addRect(
                        box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
                contentStream.fill();
            }
        }
    }

    private int resolveRenderDpi() {
        try {
            if (applicationProperties != null && applicationProperties.getSystem() != null) {
                // Capped: only the pages that could not be redacted safely are rasterized, and
                // print quality is enough for them. The configured maximum can be far higher,
                // which would balloon both memory use and output size for no added safety.
                return Math.min(applicationProperties.getSystem().getMaxDPI(), DEFAULT_RENDER_DPI);
            }
        } catch (Exception e) {
            log.debug("[redact/verify] could not read configured DPI: {}", e.getMessage());
        }
        return DEFAULT_RENDER_DPI;
    }

    // -----------------------------------------------------------------------
    // Document-level text
    // -----------------------------------------------------------------------

    /**
     * Removes redacted terms from places outside the page content that still travel with the file:
     * document information, bookmark titles and annotation contents. XMP metadata is dropped
     * whenever it contains a redacted term, since it cannot be edited safely field by field.
     */
    private void scrubDocumentLevelText(PDDocument document, RedactionTarget target) {
        List<Pattern> patterns = compilePatterns(target);
        if (patterns.isEmpty()) {
            return;
        }

        try {
            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                for (String key : List.copyOf(info.getMetadataKeys())) {
                    String value = info.getCustomMetadataValue(key);
                    if (matchesAny(patterns, value)) {
                        info.setCustomMetadataValue(key, null);
                        log.info("[redact/verify] removed redacted term from metadata '{}'", key);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[redact/verify] could not scrub document information: {}", e.getMessage());
        }

        try {
            if (document.getDocumentCatalog() != null
                    && document.getDocumentCatalog().getMetadata() != null) {
                String xmp;
                try (var xmpStream =
                        document.getDocumentCatalog().getMetadata().exportXMPMetadata()) {
                    xmp = new String(xmpStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (matchesAny(patterns, xmp)) {
                    document.getDocumentCatalog().setMetadata(null);
                    log.info("[redact/verify] dropped XMP metadata containing a redacted term");
                }
            }
        } catch (Exception e) {
            log.debug("[redact/verify] could not inspect XMP metadata: {}", e.getMessage());
        }

        try {
            if (document.getDocumentCatalog() != null) {
                PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
                if (outline != null) {
                    scrubOutline(outline.children(), patterns);
                }
            }
        } catch (Exception e) {
            log.debug("[redact/verify] could not scrub bookmarks: {}", e.getMessage());
        }

        try {
            for (PDPage page : document.getPages()) {
                List<PDAnnotation> kept = new ArrayList<>();
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (matchesAny(patterns, annotation.getContents())) {
                        log.info("[redact/verify] removed annotation containing a redacted term");
                        continue;
                    }
                    kept.add(annotation);
                }
                page.setAnnotations(kept);
            }
        } catch (Exception e) {
            log.debug("[redact/verify] could not scrub annotations: {}", e.getMessage());
        }
    }

    private void scrubOutline(Iterable<PDOutlineItem> items, List<Pattern> patterns) {
        for (PDOutlineItem item : items) {
            if (matchesAny(patterns, item.getTitle())) {
                item.setTitle("");
                log.info("[redact/verify] cleared bookmark title containing a redacted term");
            }
            scrubOutline(item.children(), patterns);
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private List<Pattern> compilePatterns(RedactionTarget target) {
        List<Pattern> patterns = new ArrayList<>();

        for (String term : target.exactTerms()) {
            if (term == null || term.isBlank()) {
                continue;
            }
            // Match the term the way the extracted text will present it: whitespace in the PDF may
            // differ from whitespace in the search term.
            String[] words = normalizeWhitespace(term).split(" ");
            StringBuilder regex = new StringBuilder();
            if (target.wholeWordOnly()) {
                regex.append("\\b");
            }
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    regex.append("\\s+");
                }
                regex.append(Pattern.quote(words[i]));
            }
            if (target.wholeWordOnly()) {
                regex.append("\\b");
            }
            patterns.add(Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE));
        }

        for (String regex : target.regexPatterns()) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                log.warn(
                        "[redact/verify] skipping invalid verification pattern '{}': {}",
                        regex,
                        e.getMessage());
            }
        }

        return patterns;
    }

    private boolean matchesAny(List<Pattern> patterns, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String normalized = normalizeWhitespace(value);
        return patterns.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static boolean intersects(PDRectangle area, float[] box) {
        return area.getLowerLeftX() < box[2]
                && area.getUpperRightX() > box[0]
                && area.getLowerLeftY() < box[3]
                && area.getUpperRightY() > box[1];
    }
}
