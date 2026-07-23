import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Button as MantineButton,
  Collapse,
  Divider,
  Group,
  Image,
  Modal,
  Paper,
  Select,
  Stack,
  Switch,
  Text,
  TextInput,
} from "@mantine/core";
import DrawIcon from "@mui/icons-material/Draw";
import OpenWithIcon from "@mui/icons-material/OpenWith";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutlined";
import EditIcon from "@mui/icons-material/Edit";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";
import { useNavigation } from "@app/contexts/NavigationContext";
import { useSigningOverlay } from "@app/contexts/SigningOverlayContext";
import type {
  SignatureOverlayAPI,
  SignaturePreview,
} from "@app/components/viewer/viewerTypes";
import { SegmentedControl } from "@app/ui/SegmentedControl";
import { Button } from "@app/ui/Button";
import { SignatureCreationStep } from "@app/components/tools/certSign/steps/SignatureCreationStep";
import { type SignatureType } from "@app/components/shared/wetSignature/SignatureTypeSelector";
import apiClient from "@app/services/apiClient";
import { composeSignatureAppearance } from "@app/utils/composeSignatureAppearance";
import { useSignatureModels } from "@app/hooks/tools/serverCertSign/useSignatureModels";
import {
  useServerCertSignParameters,
  defaultParameters,
} from "@app/hooks/tools/serverCertSign/useServerCertSignParameters";
import { useServerCertSignOperation } from "@app/hooks/tools/serverCertSign/useServerCertSignOperation";

interface ServerCertificate {
  id: string;
  name: string;
  signerId?: string;
  isDefault?: boolean;
}

const STORED_TYPE: Record<SignatureType, "canvas" | "image" | "text"> = {
  draw: "canvas",
  upload: "image",
  type: "text",
};

const formatNow = (): string => {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

const ServerCertSign = (props: BaseToolProps) => {
  const { t } = useTranslation();
  const { setWorkbench } = useNavigation();
  const { setOverlay } = useSigningOverlay();
  const { models, saveModel } = useSignatureModels();

  const base = useBaseTool(
    "serverCertSign",
    useServerCertSignParameters,
    useServerCertSignOperation,
    props,
  );

  const overlayApiRef = useRef<SignatureOverlayAPI | null>(null);
  const hasOpenedViewer = useRef(false);

  const [certificates, setCertificates] = useState<ServerCertificate[]>([]);
  const [selectedCertId, setSelectedCertId] = useState<string | null>(null);
  const [placementMode, setPlacementMode] = useState(true);
  const [previewCount, setPreviewCount] = useState(0);
  const [hasSelectedAnnotation, setHasSelectedAnnotation] = useState(false);

  // The drawn/typed/uploaded signature image.
  const [signatureData, setSignatureData] = useState<string | null>(null);

  // Appearance toggles + editable text.
  const [includeImage, setIncludeImage] = useState(true);
  const [includeName, setIncludeName] = useState(true);
  const [includeId, setIncludeId] = useState(true);
  const [includeDate, setIncludeDate] = useState(true);
  const [displayName, setDisplayName] = useState("");
  const [displayId, setDisplayId] = useState("");
  const [composedImage, setComposedImage] = useState<string | null>(null);
  const [propertiesOpen, setPropertiesOpen] = useState(false);
  const [modelName, setModelName] = useState("");

  // Create-signature modal state (reuses the shared wet-signature creation flow).
  const [createOpen, setCreateOpen] = useState(false);
  const [createType, setCreateType] = useState<SignatureType>("draw");
  const [createSignature, setCreateSignature] = useState<string | null>(null);
  const [textValue, setTextValue] = useState("");
  const [fontFamily, setFontFamily] = useState("Helvetica");
  const [fontSize, setFontSize] = useState(16);
  const [textColor, setTextColor] = useState("#000000");

  const selectedCert =
    certificates.find((c) => c.id === selectedCertId) ??
    certificates.find((c) => c.isDefault) ??
    certificates[0] ??
    null;
  const file = base.selectedFiles[0] ?? null;

  useEffect(() => {
    apiClient
      .get<ServerCertificate[]>("/api/v1/certificates")
      .then((r) => {
        const list = r.data ?? [];
        setCertificates(list);
        const def = list.find((c) => c.isDefault) ?? list[0];
        if (def) setSelectedCertId(def.id);
      })
      .catch(() => setCertificates([]));
  }, []);

  useEffect(() => {
    if (selectedCert) base.params.updateParameter("certId", selectedCert.id);
  }, [selectedCert?.id]);

  // Pre-fill editable name/id from the selected certificate.
  useEffect(() => {
    if (selectedCert) {
      setDisplayName(selectedCert.name ?? "");
      setDisplayId(selectedCert.signerId ?? "");
    }
  }, [selectedCert?.id, selectedCert?.signerId]);

  useEffect(() => {
    if (base.selectedFiles.length > 0 && !hasOpenedViewer.current) {
      setWorkbench("viewer");
      hasOpenedViewer.current = true;
    }
  }, [base.selectedFiles.length, setWorkbench]);

  const compose = useCallback((): Promise<string> => {
    return composeSignatureAppearance({
      signatureImage: signatureData,
      name: displayName,
      signerId: displayId,
      date: formatNow(),
      includeImage,
      includeName,
      includeId,
      includeDate,
    });
  }, [
    signatureData,
    displayName,
    displayId,
    includeImage,
    includeName,
    includeId,
    includeDate,
  ]);

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

  const handleUseCreated = useCallback(() => {
    if (!createSignature) return;
    setSignatureData(createSignature);
    setPlacementMode(true);
    setCreateOpen(false);
  }, [createSignature]);

  const openCreateModal = useCallback(() => {
    setCreateType("draw");
    setCreateSignature(null);
    setTextValue("");
    setCreateOpen(true);
  }, []);

  const handleSign = useCallback(async () => {
    const previews = overlayApiRef.current?.getSignaturePreviews() || [];
    const placed = previews[0];
    if (!placed || !selectedCert) return;

    const appearance = await compose();
    const params = {
      ...base.params.parameters,
      certId: selectedCert.id,
      name: displayName || selectedCert.name,
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
    selectedCert,
    base.operation,
    base.params.parameters,
    base.selectedFiles,
    compose,
    displayName,
  ]);

  const handleDeleteSelected = useCallback(() => {
    overlayApiRef.current?.deleteSelected?.();
  }, []);

  const handleSaveModel = useCallback(() => {
    if (!modelName.trim()) return;
    saveModel({
      name: modelName.trim(),
      signatureData,
      includeImage,
      includeName,
      includeId,
      includeDate,
    });
    setModelName("");
  }, [
    modelName,
    saveModel,
    signatureData,
    includeImage,
    includeName,
    includeId,
    includeDate,
  ]);

  const applyModel = useCallback(
    (id: string | null) => {
      const model = models.find((m) => m.id === id);
      if (!model) return;
      setSignatureData(model.signatureData ?? null);
      setIncludeImage(model.includeImage);
      setIncludeName(model.includeName);
      setIncludeId(model.includeId);
      setIncludeDate(model.includeDate);
    },
    [models],
  );

  const certOptions = useMemo(
    () => certificates.map((c) => ({ value: c.id, label: c.name })),
    [certificates],
  );
  const modelOptions = useMemo(
    () => models.map((m) => ({ value: m.id, label: m.name })),
    [models],
  );

  const noCertificatesAtAll = certificates.length === 0;
  const canSign = previewCount > 0 && !!selectedCert && !!signatureData;

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
      <Select
        label={t("serverCertSign.certificate.select", "Certificate")}
        data={certOptions}
        value={selectedCertId}
        onChange={setSelectedCertId}
        allowDeselect={false}
        comboboxProps={{ withinPortal: true }}
      />
    ),
  });

  if (base.selectedFiles.length > 0 && !noCertificatesAtAll) {
    steps.push({
      title: t("serverCertSign.steps.appearance", "Signature appearance"),
      isCollapsed: false,
      onCollapsedClick: undefined,
      content: (
        <Stack gap="sm">
          {/* 1) The signature (create / change) */}
          <Paper withBorder p="xs" radius="md">
            <Group justify="space-between" wrap="nowrap">
              <div style={{ flex: 1, minWidth: 0 }}>
                <Text size="xs" c="dimmed">
                  {t("serverCertSign.signature.title", "Signature")}
                </Text>
                {signatureData ? (
                  <Image
                    src={signatureData}
                    alt="signature"
                    fit="contain"
                    h={40}
                    style={{ backgroundColor: "#fff", borderRadius: 6 }}
                  />
                ) : (
                  <Text size="sm" c="dimmed">
                    {t("serverCertSign.signature.none", "No signature yet")}
                  </Text>
                )}
              </div>
              <Button
                variant="secondary"
                leftSection={<EditIcon sx={{ fontSize: "1rem" }} />}
                onClick={openCreateModal}
              >
                {signatureData
                  ? t("serverCertSign.signature.change", "Change")
                  : t("serverCertSign.signature.create", "Create")}
              </Button>
            </Group>
          </Paper>

          {/* Saved appearance models */}
          {modelOptions.length > 0 && (
            <Select
              label={t("serverCertSign.model.saved", "Saved appearance")}
              placeholder={t("serverCertSign.model.pick", "Choose a model")}
              data={modelOptions}
              onChange={applyModel}
              clearable
              comboboxProps={{ withinPortal: true }}
            />
          )}

          {/* Live preview */}
          {composedImage && (
            <Paper withBorder p="xs" radius="md">
              <Text size="xs" c="dimmed" mb={4}>
                {t("serverCertSign.appearance.preview", "Preview")}
              </Text>
              <Image
                src={composedImage}
                alt="preview"
                fit="contain"
                h={80}
                style={{ backgroundColor: "#fff" }}
              />
            </Paper>
          )}

          {/* 2) Appearance properties — collapsed by default */}
          <MantineButton
            variant="subtle"
            size="xs"
            justify="space-between"
            fullWidth
            rightSection={
              <ExpandMoreIcon
                sx={{
                  fontSize: "1.1rem",
                  transform: propertiesOpen ? "rotate(180deg)" : "none",
                  transition: "transform 150ms",
                }}
              />
            }
            onClick={() => setPropertiesOpen((o) => !o)}
          >
            {t("serverCertSign.appearance.customize", "Customize appearance")}
          </MantineButton>
          <Collapse in={propertiesOpen}>
            <Stack gap="sm" pt="xs">
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
                  label={t("serverCertSign.appearance.id", "DNI/CIF")}
                  checked={includeId}
                  onChange={(e) => setIncludeId(e.currentTarget.checked)}
                />
                <Switch
                  label={t("serverCertSign.appearance.date", "Date")}
                  checked={includeDate}
                  onChange={(e) => setIncludeDate(e.currentTarget.checked)}
                />
              </Group>
              {includeName && (
                <TextInput
                  label={t("serverCertSign.appearance.name", "Name")}
                  value={displayName}
                  onChange={(e) => setDisplayName(e.currentTarget.value)}
                />
              )}
              {includeId && (
                <TextInput
                  label={t("serverCertSign.appearance.id", "DNI/CIF")}
                  value={displayId}
                  onChange={(e) => setDisplayId(e.currentTarget.value)}
                />
              )}
              <Group gap="xs" align="flex-end" wrap="nowrap">
                <TextInput
                  label={t("serverCertSign.model.saveAs", "Save appearance as")}
                  placeholder={t(
                    "serverCertSign.model.namePlaceholder",
                    "Model name",
                  )}
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
          </Collapse>

          <Divider />

          {/* 3) Placement controls — at the end of the block */}
          <SegmentedControl
            fullWidth
            size="xs"
            value={placementMode ? "place" : "move"}
            onChange={(v) => setPlacementMode(v === "place")}
            ariaLabel={t("serverCertSign.placement.title", "Place or move")}
            options={[
              {
                value: "place",
                label: (
                  <Group gap={6} wrap="nowrap" justify="center">
                    <DrawIcon sx={{ fontSize: "1.1rem" }} />
                    <span>{t("serverCertSign.placement.place", "Place")}</span>
                  </Group>
                ),
              },
              {
                value: "move",
                label: (
                  <Group gap={6} wrap="nowrap" justify="center">
                    <OpenWithIcon sx={{ fontSize: "1.1rem" }} />
                    <span>{t("serverCertSign.placement.move", "Move")}</span>
                  </Group>
                ),
              },
            ]}
          />
          <Button
            variant="tertiary"
            accent="danger"
            leftSection={<DeleteOutlineIcon sx={{ fontSize: "1.1rem" }} />}
            onClick={handleDeleteSelected}
            disabled={!hasSelectedAnnotation}
            fullWidth
          >
            {t("serverCertSign.placement.delete", "Delete selected signature")}
          </Button>
        </Stack>
      ),
    });
  }

  return (
    <>
      {createToolFlow({
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
          paramsValid: canSign,
        },
        review: {
          isVisible: base.hasResults,
          operation: base.operation,
          title: t("serverCertSign.sign.results", "Signed PDF"),
          onFileClick: base.handleThumbnailClick,
          onUndo: base.handleUndo,
        },
      })}

      <Modal
        opened={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t("serverCertSign.signature.create", "Create signature")}
        size="md"
        withinPortal
      >
        <SignatureCreationStep
          signatureType={createType}
          onSignatureTypeChange={setCreateType}
          signature={createSignature}
          onSignatureChange={setCreateSignature}
          signatureText={textValue}
          fontFamily={fontFamily}
          fontSize={fontSize}
          textColor={textColor}
          onSignatureTextChange={setTextValue}
          onFontFamilyChange={setFontFamily}
          onFontSizeChange={setFontSize}
          onTextColorChange={setTextColor}
          onNext={handleUseCreated}
          nextLabel={t("serverCertSign.signature.use", "Use signature")}
        />
      </Modal>
    </>
  );
};

ServerCertSign.tool = () => useServerCertSignOperation;
ServerCertSign.getDefaultParameters = () => defaultParameters;

export default ServerCertSign as ToolComponent;
