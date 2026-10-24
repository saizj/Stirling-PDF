import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const fileManagement = vi.hoisted(() => ({ addFiles: vi.fn() }));

const stash = vi.hoisted(() => ({
  stashLaunchedFiles: vi.fn(async (_files: File[]) => {}),
  readStashedLaunchedFiles: vi.fn(async (): Promise<File[]> => []),
  clearStashedLaunchedFiles: vi.fn(async () => {}),
}));

vi.mock("@editor/hooks/launchedFilesStash", () => stash);

vi.mock("@editor/contexts/file/fileHooks", () => ({
  useFileManagement: () => ({ addFiles: fileManagement.addFiles }),
}));

import { useLaunchedFiles } from "@editor/hooks/useLaunchedFiles";

type LaunchConsumer = (params: {
  readonly files: readonly FileSystemFileHandle[];
}) => void | Promise<void>;

function stubLaunchQueue() {
  const setConsumer = vi.fn<(consumer: LaunchConsumer) => void>();
  window.launchQueue = { setConsumer };
  return setConsumer;
}

function fileHandle(file: File): FileSystemFileHandle {
  return { getFile: async () => file } as unknown as FileSystemFileHandle;
}

const pdf = () =>
  new File(["%PDF-1.7"], "invoice.pdf", { type: "application/pdf" });

describe("useLaunchedFiles", () => {
  afterEach(async () => {
    delete window.launchQueue;
    fileManagement.addFiles.mockReset();
    vi.useRealTimers();
    stash.stashLaunchedFiles.mockClear();
    stash.readStashedLaunchedFiles.mockReset();
    stash.readStashedLaunchedFiles.mockResolvedValue([]);
    stash.clearStashedLaunchedFiles.mockClear();
  });

  it("does nothing when the browser has no File Handling API", () => {
    expect(() => renderHook(() => useLaunchedFiles())).not.toThrow();
    expect(fileManagement.addFiles).not.toHaveBeenCalled();
  });

  it("adds the files the OS handed over and selects them", async () => {
    const setConsumer = stubLaunchQueue();
    renderHook(() => useLaunchedFiles());

    const file = pdf();
    await setConsumer.mock.calls[0][0]({ files: [fileHandle(file)] });

    await waitFor(() =>
      expect(fileManagement.addFiles).toHaveBeenCalledWith([file], {
        selectFiles: true,
      }),
    );
  });

  it("ignores a launch that carries no files", async () => {
    const setConsumer = stubLaunchQueue();
    renderHook(() => useLaunchedFiles());

    await setConsumer.mock.calls[0][0]({ files: [] });

    expect(fileManagement.addFiles).not.toHaveBeenCalled();
  });

  it("registers a single consumer across re-renders", () => {
    const setConsumer = stubLaunchQueue();
    const { rerender } = renderHook(() => useLaunchedFiles());

    fileManagement.addFiles = vi.fn();
    rerender();

    expect(setConsumer).toHaveBeenCalledOnce();
  });

  it("stashes launched files and only clears them after a grace period", async () => {
    vi.useFakeTimers();
    const setConsumer = stubLaunchQueue();
    renderHook(() => useLaunchedFiles());

    const file = pdf();
    await setConsumer.mock.calls[0][0]({ files: [fileHandle(file)] });

    expect(stash.stashLaunchedFiles).toHaveBeenCalledWith([file]);
    expect(stash.clearStashedLaunchedFiles).not.toHaveBeenCalled();
    vi.advanceTimersByTime(20_000);
    expect(stash.clearStashedLaunchedFiles).toHaveBeenCalledOnce();
  });

  it("reopens files a discarded page load left in the stash", async () => {
    const file = pdf();
    stash.readStashedLaunchedFiles.mockResolvedValue([file]);

    renderHook(() => useLaunchedFiles());

    await waitFor(() =>
      expect(fileManagement.addFiles).toHaveBeenCalledWith([file], {
        selectFiles: true,
      }),
    );
    await waitFor(() =>
      expect(stash.clearStashedLaunchedFiles).toHaveBeenCalledOnce(),
    );
  });
});
