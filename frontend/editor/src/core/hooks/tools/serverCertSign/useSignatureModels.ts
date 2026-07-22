import { useCallback, useEffect, useState } from "react";

/** A saved Adobe-style signature appearance model (persisted per browser). */
export interface SignatureModel {
  id: string;
  name: string;
  signatureData?: string | null;
  signatureType?: "canvas" | "image" | "text";
  includeImage: boolean;
  includeName: boolean;
  includeDate: boolean;
}

const STORAGE_KEY = "blasai_signature_models";

const load = (): SignatureModel[] => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as SignatureModel[]) : [];
  } catch {
    return [];
  }
};

const persist = (models: SignatureModel[]) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(models));
  } catch {
    // Storage full / unavailable — non-fatal.
  }
};

export function useSignatureModels() {
  const [models, setModels] = useState<SignatureModel[]>([]);

  useEffect(() => {
    setModels(load());
  }, []);

  const saveModel = useCallback((model: Omit<SignatureModel, "id">): SignatureModel => {
    const id =
      typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID()
        : `m_${models.length}_${model.name}`;
    const saved: SignatureModel = { ...model, id };
    setModels((prev) => {
      const next = [...prev, saved];
      persist(next);
      return next;
    });
    return saved;
  }, [models.length]);

  const deleteModel = useCallback((id: string) => {
    setModels((prev) => {
      const next = prev.filter((m) => m.id !== id);
      persist(next);
      return next;
    });
  }, []);

  return { models, saveModel, deleteModel };
}
