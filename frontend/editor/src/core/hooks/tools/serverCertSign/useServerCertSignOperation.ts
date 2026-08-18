import { useTranslation } from "react-i18next";
import {
  defineCustomTool,
  useToolOperation,
  CustomProcessorResult,
} from "@app/hooks/tools/shared/useToolOperation";
import apiClient from "@app/services/apiClient";
import { createFileFromApiResponse } from "@app/utils/fileResponseUtils";
import {
  ServerCertSignParameters,
  defaultParameters,
} from "@app/hooks/tools/serverCertSign/useServerCertSignParameters";

/** Sanitises a user-entered file name and makes sure it ends in ".pdf". */
function toPdfFileName(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|]/g, "").trim();
  return /\.pdf$/i.test(cleaned) ? cleaned : `${cleaned}.pdf`;
}

async function processServerCertSign(
  params: ServerCertSignParameters,
  files: File[],
): Promise<CustomProcessorResult> {
  const file = files[0];
  if (!file) throw new Error("No file selected");
  if (!params.certId) throw new Error("No certificate selected");
  if (!params.placement) throw new Error("No signature placed on the document");
  if (!params.placement.signatureData && params.placement.lines.length === 0) {
    throw new Error("The signature appearance is empty");
  }

  const { placement } = params;
  const formData = new FormData();
  formData.append("fileInput", file);
  formData.append("certId", params.certId);
  if (placement.signatureData) {
    formData.append("signatureImage", placement.signatureData);
  }
  placement.lines.forEach((line) => formData.append("appearanceLine", line));
  formData.append("x", String(placement.x));
  formData.append("y", String(placement.y));
  formData.append("width", String(placement.width));
  formData.append("height", String(placement.height));
  // Backend expects a 1-indexed page number.
  formData.append("pageNumber", String(placement.page + 1));
  if (params.name) formData.append("name", params.name);
  if (params.reason) formData.append("reason", params.reason);
  if (params.location) formData.append("location", params.location);

  const response = await apiClient.post("/api/v1/certificates/sign", formData, {
    responseType: "blob",
  });

  const signed = createFileFromApiResponse(
    response.data,
    response.headers,
    file.name,
  );
  // The backend always names the download "signed.pdf" — honour the name the
  // user chose in the panel instead.
  const desiredName = params.outputFileName?.trim();
  if (!desiredName) return { files: [signed], consumedAllInputs: true };

  const renamed = new File([signed], toPdfFileName(desiredName), {
    type: signed.type,
  });
  return { files: [renamed], consumedAllInputs: true };
}

export const serverCertSignOperationConfig = defineCustomTool({
  customProcessor: processServerCertSign,
  operationType: "serverCertSign",
  defaultParameters,
});

export const useServerCertSignOperation = () => {
  const { t } = useTranslation();
  return useToolOperation<ServerCertSignParameters>({
    ...serverCertSignOperationConfig,
    getErrorMessage: () =>
      t(
        "serverCertSign.error.failed",
        "An error occurred while signing the document.",
      ),
  });
};
