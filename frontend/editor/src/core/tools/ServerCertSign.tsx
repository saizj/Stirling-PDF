import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Stack, Text } from "@mantine/core";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";
import { useNavigation } from "@app/contexts/NavigationContext";
import { useSigningOverlay } from "@app/contexts/SigningOverlayContext";
import type {
  SignatureOverlayAPI,
  SignaturePreview,
} from "@app/components/viewer/viewerTypes";
import SignControlsPanel from "@app/components/tools/certSign/panels/SignControlsPanel";
import type { SignParameters } from "@app/hooks/tools/sign/useSignParameters";
import apiClient from "@app/services/apiClient";
import {
  useServerCertSignParameters,
  defaultParameters,
} from "@app/hooks/tools/serverCertSign/useServerCertSignParameters";
import { useServerCertSignOperation } from "@app/hooks/tools/serverCertSign/useServerCertSignOperation";

interface ServerCertificate {
  id: string;
  name: string;
  isDefault?: boolean;
}

const ServerCertSign = (props: BaseToolProps) => {
  const { t } = useTranslation();
  const { setWorkbench } = useNavigation();
  const { setOverlay } = useSigningOverlay();

  const base = useBaseTool(
    "serverCertSign",
    useServerCertSignParameters,
    useServerCertSignOperation,
    props,
  );

  const overlayApiRef = useRef<SignatureOverlayAPI | null>(null);
  const hasOpenedViewer = useRef(false);

  const [certificates, setCertificates] = useState<ServerCertificate[]>([]);
  const [placementMode, setPlacementMode] = useState(true);
  const [previewCount, setPreviewCount] = useState(0);
  const [hasSelectedAnnotation, setHasSelectedAnnotation] = useState(false);
  const [signatureConfig, setSignatureConfig] = useState<SignParameters | null>(
    {
      signatureType: "canvas",
      signerName: "",
      fontFamily: "Helvetica",
      fontSize: 16,
      textColor: "#000000",
    },
  );

  const activeCert =
    certificates.find((c) => c.isDefault) ?? certificates[0] ?? null;
  const file = base.selectedFiles[0] ?? null;

  // Load the stored certificates so we know which one is active.
  useEffect(() => {
    apiClient
      .get<ServerCertificate[]>("/api/v1/certificates")
      .then((r) => setCertificates(r.data ?? []))
      .catch(() => setCertificates([]));
  }, []);

  // Keep certId in params in sync with the active certificate.
  useEffect(() => {
    if (activeCert) base.params.updateParameter("certId", activeCert.id);
  }, [activeCert?.id]);

  // Open the viewer once a file is selected so the user can place the box.
  useEffect(() => {
    if (base.selectedFiles.length > 0 && !hasOpenedViewer.current) {
      setWorkbench("viewer");
      hasOpenedViewer.current = true;
    }
  }, [base.selectedFiles.length, setWorkbench]);

  const handlePreviewsChange = useCallback((previews: SignaturePreview[]) => {
    setPreviewCount(previews.length);
  }, []);

  // Drive the shared viewer overlay: show the doc and enable placement of the
  // drawn signature.
  const placementData = signatureConfig?.signatureData;
  const placementType = signatureConfig?.signatureType;
  useEffect(() => {
    setOverlay({
      file,
      signaturePlacementMode: placementMode,
      signaturePlacementData: placementData,
      signaturePlacementType: placementType,
      onSignaturePreviewsChange: handlePreviewsChange,
      signatureOverlayApiRef: overlayApiRef,
    });
  }, [
    file,
    placementMode,
    placementData,
    placementType,
    handlePreviewsChange,
    setOverlay,
  ]);

  // Clear the overlay when leaving the tool.
  useEffect(() => () => setOverlay(null), [setOverlay]);

  // Poll the overlay for a selected box (drives the delete control).
  useEffect(() => {
    const check = () =>
      setHasSelectedAnnotation(Boolean(overlayApiRef.current?.hasSelected?.()));
    check();
    const id = setInterval(check, 350);
    return () => clearInterval(id);
  }, []);

  const handleSign = useCallback(async () => {
    const previews = overlayApiRef.current?.getSignaturePreviews() || [];
    const placed = previews[0];
    if (!placed || !activeCert) return;

    const params = {
      ...base.params.parameters,
      certId: activeCert.id,
      name: signatureConfig?.signerName || base.params.parameters.name,
      placement: {
        signatureData: placed.signatureData,
        page: placed.pageIndex,
        x: placed.x,
        y: placed.y,
        width: placed.width,
        height: placed.height,
      },
    };
    await base.operation.executeOperation(params, base.selectedFiles);
  }, [activeCert, base.operation, base.params.parameters, base.selectedFiles, signatureConfig?.signerName]);

  const handleDeleteSelected = useCallback(() => {
    overlayApiRef.current?.deleteSelected?.();
  }, []);

  const noCertificate = certificates.length > 0 && !activeCert;
  const noCertificatesAtAll = certificates.length === 0;

  const steps = [];

  steps.push({
    title: t("serverCertSign.steps.certificate", "Certificate"),
    isCollapsed: false,
    onCollapsedClick: undefined,
    content: noCertificatesAtAll ? (
      <Alert color="yellow" variant="light">
        {t(
          "serverCertSign.noCertificate",
          "No certificate stored. Add one in Settings → Certificates first.",
        )}
      </Alert>
    ) : (
      <Text size="sm">
        {t("serverCertSign.usingCertificate", "Signing with")}:{" "}
        <b>{activeCert?.name}</b>
      </Text>
    ),
  });

  if (base.selectedFiles.length > 0 && !noCertificatesAtAll) {
    steps.push({
      title: t("serverCertSign.steps.place", "Draw & place signature"),
      isCollapsed: false,
      onCollapsedClick: undefined,
      content: (
        <SignControlsPanel
          placementMode={placementMode}
          onPlacementModeChange={setPlacementMode}
          onSignatureSelected={setSignatureConfig}
          onComplete={handleSign}
          canComplete={previewCount > 0}
          signatureConfig={signatureConfig}
          hasSelectedAnnotation={hasSelectedAnnotation}
          onDeleteSelected={handleDeleteSelected}
        />
      ),
    });
  }

  return createToolFlow({
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: base.selectedFiles.length > 0,
    },
    steps,
    executeButton: {
      text: t("serverCertSign.sign.submit", "Sign PDF"),
      isVisible: !base.hasResults,
      loadingText: t("loading"),
      onClick: handleSign,
      endpointEnabled: true,
      paramsValid: previewCount > 0 && !!activeCert && !noCertificate,
    },
    review: {
      isVisible: base.hasResults,
      operation: base.operation,
      title: t("serverCertSign.sign.results", "Signed PDF"),
      onFileClick: base.handleThumbnailClick,
      onUndo: base.handleUndo,
    },
  });
};

ServerCertSign.tool = () => useServerCertSignOperation;
ServerCertSign.getDefaultParameters = () => defaultParameters;

export default ServerCertSign as ToolComponent;
