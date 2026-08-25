import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";

/** A signature placed on the PDF: mark + rectangle as page fractions (top-left origin). */
export interface PlacedSignature {
  /** Base64 PNG of the user's own image (drawing/logo); empty when the mark is text only. */
  signatureData: string;
  /**
   * The text block, in order — the first line is the emphasised one. Sent separately from the
   * image so the backend can draw it as real PDF text instead of stamping a picture of it.
   */
  lines: string[];
  page: number; // 0-indexed page
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ServerCertSignParameters extends BaseParameters {
  /** Id of the stored server certificate to sign with. */
  certId: string;
  reason: string;
  location: string;
  name: string;
  /** File name for the signed PDF (without extension; ".pdf" is appended). */
  outputFileName: string;
  /** The placed signature (set from the viewer overlay when the user drops the box). */
  placement?: PlacedSignature;
}

export const defaultParameters: ServerCertSignParameters = {
  certId: "",
  reason: "",
  location: "",
  name: "",
  outputFileName: "",
};

export type ServerCertSignParametersHook =
  BaseParametersHook<ServerCertSignParameters>;

/**
 * Signing needs a certificate to sign with and a box to draw the signature in. Shared with the
 * operation config as `validateParams` so a composer that never renders the panel judges the step
 * by the same rule the Run button does.
 */
export const validateServerCertSignParameters = (
  params: ServerCertSignParameters,
) => !!params.certId && !!params.placement;

export const useServerCertSignParameters = (): ServerCertSignParametersHook => {
  return useBaseParameters<ServerCertSignParameters>({
    defaultParameters,
    endpointName: "",
    validateFn: validateServerCertSignParameters,
  });
};
