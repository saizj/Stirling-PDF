import { useEffect, useRef, useImperativeHandle, forwardRef } from "react";
import { useRedaction as useEmbedPdfRedaction } from "@embedpdf/plugin-redaction/react";
import { useActiveDocumentId } from "@app/components/viewer/useActiveDocumentId";

export interface RedactionPendingTrackerAPI {
  /**
   * Permanently removes the content under every pending redaction. The returned
   * promise resolves only once the engine has finished; callers must await it
   * before exporting, otherwise the export still contains the redacted content.
   */
  commitAllPending: () => Promise<void>;
  getPendingCount: () => number;
}

export const RedactionPendingTracker = forwardRef<RedactionPendingTrackerAPI>(
  function RedactionPendingTracker(_, ref) {
    const activeDocumentId = useActiveDocumentId();

    // Don't render the inner component until we have a valid document ID
    if (!activeDocumentId) {
      return null;
    }

    return (
      <RedactionPendingTrackerInner documentId={activeDocumentId} ref={ref} />
    );
  },
);

const RedactionPendingTrackerInner = forwardRef<
  RedactionPendingTrackerAPI,
  { documentId: string }
>(function RedactionPendingTrackerInner({ documentId }, ref) {
  const { state, provides } = useEmbedPdfRedaction(documentId);

  const pendingCountRef = useRef(0);

  // Expose API through ref
  useImperativeHandle(
    ref,
    () => ({
      commitAllPending: async () => {
        if (!provides?.commitAllPending) {
          throw new Error("Redaction engine is not available");
        }
        await provides.commitAllPending().toPromise();
      },
      // Read the live plugin state rather than the React-synced ref: callers
      // check this immediately after committing, before React has re-rendered.
      getPendingCount: () => {
        try {
          const pendingCount = provides?.getState()?.pendingCount;
          if (typeof pendingCount === "number") return pendingCount;
        } catch {
          // Plugin has no state for this document yet — fall back to the ref.
        }
        return pendingCountRef.current;
      },
    }),
    [provides],
  );

  // Update ref when pending count changes
  useEffect(() => {
    pendingCountRef.current = state?.pendingCount ?? 0;
  }, [state?.pendingCount]);

  return null;
});
