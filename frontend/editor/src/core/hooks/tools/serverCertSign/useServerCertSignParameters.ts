import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";

/** A signature placed on the PDF: image + rectangle as page fractions (top-left origin). */
export interface PlacedSignature {
  signatureData: string; // Base64 PNG
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
  /** The placed signature (set from the viewer overlay when the user drops the box). */
  placement?: PlacedSignature;
}

export const defaultParameters: ServerCertSignParameters = {
  certId: "",
  reason: "",
  location: "",
  name: "",
};

export type ServerCertSignParametersHook =
  BaseParametersHook<ServerCertSignParameters>;

export const useServerCertSignParameters = (): ServerCertSignParametersHook => {
  return useBaseParameters<ServerCertSignParameters>({
    defaultParameters,
    endpointName: "",
    validateFn: (params) => !!params.certId && !!params.placement,
  });
};
