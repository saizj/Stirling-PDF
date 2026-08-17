import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const fileManagement = vi.hoisted(() => ({ addFiles: vi.fn() }));

vi.mock("@app/contexts/file/fileHooks", () => ({
  useFileManagement: () => ({ addFiles: fileManagement.addFiles }),
}));

import { useLaunchedFiles } from "@app/hooks/useLaunchedFiles";

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
  afterEach(() => {
    delete window.launchQueue;
    fileManagement.addFiles.mockReset();
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
});
