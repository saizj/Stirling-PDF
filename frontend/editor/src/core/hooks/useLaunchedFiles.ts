import { useEffect, useRef } from "react";
import { useFileManagement } from "@app/contexts/file/fileHooks";

interface LaunchParams {
  readonly files: readonly FileSystemFileHandle[];
}

interface LaunchQueue {
  setConsumer(consumer: (params: LaunchParams) => void | Promise<void>): void;
}

declare global {
  interface Window {
    launchQueue?: LaunchQueue;
  }
}

/**
 * Receives the files the operating system hands over when the installed app is
 * the registered handler for a file type — double-clicking a PDF, or "Open
 * with". The association itself is declared in the web manifest
 * (`file_handlers`); Chromium delivers the files through `window.launchQueue`.
 *
 * The consumer is registered exactly once per page load: launchQueue holds the
 * launch params until a consumer exists, and a second setConsumer call would
 * not replay what the first one already received.
 */
export function useLaunchedFiles(): void {
  const { addFiles } = useFileManagement();
  const addFilesRef = useRef(addFiles);

  useEffect(() => {
    addFilesRef.current = addFiles;
  }, [addFiles]);

  useEffect(() => {
    const launchQueue = window.launchQueue;
    if (!launchQueue) {
      return;
    }

    launchQueue.setConsumer(async ({ files: handles }) => {
      if (handles.length === 0) {
        return;
      }
      try {
        const files = await Promise.all(
          handles.map((handle) => handle.getFile()),
        );
        await addFilesRef.current(files, { selectFiles: true });
      } catch (error) {
        console.error("Failed to add files opened from the OS:", error);
      }
    });
  }, []);
}
