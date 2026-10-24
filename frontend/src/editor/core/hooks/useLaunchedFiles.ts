import { useEffect, useRef } from "react";
import { useFileManagement } from "@editor/contexts/file/fileHooks";
import {
  clearStashedLaunchedFiles,
  readStashedLaunchedFiles,
  stashLaunchedFiles,
} from "@editor/hooks/launchedFilesStash";

interface LaunchParams {
  readonly files: readonly FileSystemFileHandle[];
}

interface LaunchQueue {
  setConsumer(consumer: (params: LaunchParams) => void | Promise<void>): void;
}

const STASH_GRACE_MS = 20_000;

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

  // A previous load may have been discarded before its launch files were added
  // (see launchedFilesStash); open them now.
  useEffect(() => {
    void (async () => {
      const stashed = await readStashedLaunchedFiles();
      if (stashed.length === 0) return;
      try {
        await addFilesRef.current(stashed, { selectFiles: true });
        await clearStashedLaunchedFiles();
      } catch (error) {
        console.error("Failed to reopen files handed over by the OS:", error);
      }
    })();
  }, []);

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
        await stashLaunchedFiles(files);
        await addFilesRef.current(files, { selectFiles: true });
        // The page can still be discarded after the files are added: the boot
        // requests that discover an expired session finish later. Keep the
        // stash until they have had time to.
        window.setTimeout(
          () => void clearStashedLaunchedFiles(),
          STASH_GRACE_MS,
        );
      } catch (error) {
        console.error("Failed to add files opened from the OS:", error);
      }
    });
  }, []);
}
