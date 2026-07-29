import { useCallback, useEffect, useState } from "react";
import apiClient from "@app/services/apiClient";

/** A saved Adobe-style signature appearance model (persisted on the server). */
export interface SignatureModel {
  id: string;
  name: string;
  signatureData?: string | null;
  signatureType?: "canvas" | "image" | "text";
  includeImage: boolean;
  includeName: boolean;
  includeId: boolean;
  includeDate: boolean;
}

const ENDPOINT = "/api/v1/signature-appearances";

/** Appearances used to live per-browser; these keys drive the one-time upload to the server. */
const LEGACY_STORAGE_KEY = "blasai_signature_models";
const MIGRATED_KEY = "blasai_signature_models_migrated";

const readLegacyModels = (): SignatureModel[] => {
  try {
    if (localStorage.getItem(MIGRATED_KEY)) return [];
    const raw = localStorage.getItem(LEGACY_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as SignatureModel[]) : [];
  } catch {
    return [];
  }
};

const markMigrated = () => {
  try {
    localStorage.setItem(MIGRATED_KEY, "1");
  } catch {
    // Storage unavailable — worst case we retry the migration next time.
  }
};

export function useSignatureModels() {
  const [models, setModels] = useState<SignatureModel[]>([]);

  const refresh = useCallback(async (): Promise<SignatureModel[]> => {
    const { data } = await apiClient.get<SignatureModel[]>(ENDPOINT);
    const list = data ?? [];
    setModels(list);
    return list;
  }, []);

  useEffect(() => {
    let cancelled = false;
    const init = async () => {
      try {
        const serverModels = await refresh();
        if (cancelled) return;

        // First run on this browser: push any locally saved appearances up so they
        // become available everywhere, then never look at localStorage again.
        const legacy = readLegacyModels();
        if (legacy.length === 0) return;
        const existingNames = new Set(serverModels.map((m) => m.name));
        const pending = legacy.filter((m) => !existingNames.has(m.name));
        for (const model of pending) {
          const { id: _id, ...body } = model;
          await apiClient.post(ENDPOINT, body);
        }
        markMigrated();
        if (!cancelled && pending.length > 0) await refresh();
      } catch {
        // Offline or endpoint unavailable — the tool still works, just without saved models.
      }
    };
    init();
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  const saveModel = useCallback(
    async (model: Omit<SignatureModel, "id">): Promise<SignatureModel | null> => {
      try {
        const { data } = await apiClient.post<SignatureModel>(ENDPOINT, model);
        await refresh();
        return data ?? null;
      } catch {
        return null;
      }
    },
    [refresh],
  );

  const deleteModel = useCallback(
    async (id: string): Promise<void> => {
      try {
        await apiClient.delete(`${ENDPOINT}/${id}`);
        await refresh();
      } catch {
        // Nothing to do — the list stays as it is.
      }
    },
    [refresh],
  );

  return { models, saveModel, deleteModel, refresh };
}
