import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Button as MantineButton,
  Divider,
  Group,
  Image,
  Paper,
  Select,
  Stack,
  Switch,
  Text,
  TextInput,
} from "@mantine/core";
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
import { composeSignatureAppearance } from "@app/utils/composeSignatureAppearance";
import {
  useSignatureModels,
  type SignatureModel,
} from "@app/hooks/tools/serverCertSign/useSignatureModels";
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

const formatNow = (): string => {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

const emptySignatureConfig = (): SignParameters => ({
  signatureType: "canvas",
  signerName: "",
  fontFamily: "Helvetica",
  fontSize: 16,
  textColor: "#000000",
});

const ServerCertSign = (props: BaseToolProps) => {
  const { t } = useTranslation();
  const { setWorkbench } = useNavigation();
  const { setOverlay } = useSigningOverlay();
  const { models, saveModel, deleteModel } = useSignatureModels();

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
    emptySignatureConfig(),
  );

  // Adobe-style appearance toggles + composed preview.
  const [includeImage, setIncludeImage] = useState(true);
  const [includeName, setIncludeName] = useState(true);
  const [includeDate, setIncludeDate] = useState(true);
  const [composedImage, setComposedImage] = useState<string | null>(null);
  const [modelName, setModelName] = useState("");

  const activeCert =
    certificates.find((c) => c.isDefault) ?? certificates[0] ?? null;
  const file = base.selectedFiles[0] ?? null;

  useEffect(() => {
    apiClient
      .get<ServerCertificate[]>("/api/v1/certificates")
      .then((r) => setCertificates(r.data ?? []))
      .catch(() => setCertificates([]));
  }, []);

  useEffect(() => {
    if (activeCert) base.params.updateParameter("certId", activeCert.id);
  }, [activeCert?.id]);

  useEffect(() => {
    if (base.selectedFiles.length > 0 && !hasOpenedViewer.current) {
      setWorkbench("viewer");
      hasOpenedViewer.current = true;
    }
  }, [base.selectedFiles.length, setWorkbench]);

  const compose = useCallback((): Promise<string> => {
    return composeSignatureAppearance({
      signatureImage: signatureConfig?.signatureData,
      name: activeCert?.name,
      date: formatNow(),
      includeImage,
      includeName,
      includeDate,
    });
  }, [
    signatureConfig?.signatureData,
    activeCert?.name,
    includeImage,
    includeName,
    includeDate,
  ]);

  // Recompose the appearance preview whenever inputs change.
  useEffect(() => {
    let cancelled = false;
    compose().then((data) => {
      if (!cancelled) setComposedImage(data || null);
    });
    return () => {
      cancelled = true;
    };
  }, [compose]);

  const handlePreviewsChange = useCallback((previews: SignaturePreview[]) => {
    setPreviewCount(previews.length);
  }, []);

  // Drive the shared viewer overlay with the COMPOSED appearance.
  useEffect(() => {
    setOverlay({
      file,
      signaturePlacementMode: placementMode,
      signaturePlacementData: composedImage ?? undefined,
      signaturePlacementType: "image",
      onSignaturePreviewsChange: handlePreviewsChange,
      signatureOverlayApiRef: overlayApiRef,
    });
  }, [file, placementMode, composedImage, handlePreviewsChange, setOverlay]);

  useEffect(() => () => setOverlay(null), [setOverlay]);

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

    // Recompose with a fresh timestamp for the stamped appearance.
    const appearance = await compose();

    const params = {
      ...base.params.parameters,
      certId: activeCert.id,
      name: signatureConfig?.signerName || activeCert.name,
      placement: {
        signatureData: appearance || placed.signatureData,
        page: placed.pageIndex,
        x: placed.x,
        y: placed.y,
        width: placed.width,
        height: placed.height,
      },
    };
    await base.operation.executeOperation(params, base.selectedFiles);
  }, [
    activeCert,
    base.operation,
    base.params.parameters,
    base.selectedFiles,
    compose,
    signatureConfig?.signerName,
  ]);

  const handleDeleteSelected = useCallback(() => {
    overlayApiRef.current?.deleteSelected?.();
  }, []);

  const handleSaveModel = useCallback(() => {
    if (!modelName.trim()) return;
    saveModel({
      name: modelName.trim(),
      signatureData: signatureConfig?.signatureData ?? null,
      signatureType: signatureConfig?.signatureType,
      includeImage,
      includeName,
      includeDate,
    });
    setModelName("");
  }, [
    modelName,
    saveModel,
    signatureConfig,
    includeImage,
    includeName,
    includeDate,
  ]);

  const applyModel = useCallback(
    (id: string | null) => {
      const model = models.find((m) => m.id === id);
      if (!model) return;
      setSignatureConfig({
        ...emptySignatureConfig(),
        signatureType: model.signatureType ?? "canvas",
        signatureData: model.signatureData ?? undefined,
      });
      setIncludeImage(model.includeImage);
      setIncludeName(model.includeName);
      setIncludeDate(model.includeDate);
    },
    [models],
  );

  const modelOptions = useMemo(
    () => models.map((m: SignatureModel) => ({ value: m.id, label: m.name })),
    [models],
  );

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
      title: t("serverCertSign.steps.appearance", "Signature appearance"),
      isCollapsed: false,
      onCollapsedClick: undefined,
      content: (
        <Stack gap="sm">
          {modelOptions.length > 0 && (
            <Group gap="xs" align="flex-end" wrap="nowrap">
              <Select
                label={t("serverCertSign.model.saved", "Saved appearance")}
                placeholder={t("serverCertSign.model.pick", "Choose a model")}
                data={modelOptions}
                onChange={applyModel}
                clearable
                style={{ flex: 1 }}
              />
            </Group>
          )}

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

          <Group gap="lg">
            <Switch
              label={t("serverCertSign.appearance.image", "Image")}
              checked={includeImage}
              onChange={(e) => setIncludeImage(e.currentTarget.checked)}
            />
            <Switch
              label={t("serverCertSign.appearance.name", "Name")}
              checked={includeName}
              onChange={(e) => setIncludeName(e.currentTarget.checked)}
            />
            <Switch
              label={t("serverCertSign.appearance.date", "Date")}
              checked={includeDate}
              onChange={(e) => setIncludeDate(e.currentTarget.checked)}
            />
          </Group>

          {composedImage && (
            <Paper withBorder p="xs" radius="md">
              <Text size="xs" c="dimmed" mb={4}>
                {t("serverCertSign.appearance.preview", "Preview")}
              </Text>
              <Image
                src={composedImage}
                alt="signature preview"
                fit="contain"
                h={90}
                style={{ backgroundColor: "#fff" }}
              />
            </Paper>
          )}

          <Divider />
          <Group gap="xs" align="flex-end" wrap="nowrap">
            <TextInput
              label={t("serverCertSign.model.saveAs", "Save appearance as")}
              placeholder={t("serverCertSign.model.namePlaceholder", "Model name")}
              value={modelName}
              onChange={(e) => setModelName(e.currentTarget.value)}
              style={{ flex: 1 }}
            />
            <MantineButton
              variant="light"
              onClick={handleSaveModel}
              disabled={!modelName.trim()}
            >
              {t("serverCertSign.model.save", "Save")}
            </MantineButton>
          </Group>
        </Stack>
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
      paramsValid: previewCount > 0 && !!activeCert,
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
