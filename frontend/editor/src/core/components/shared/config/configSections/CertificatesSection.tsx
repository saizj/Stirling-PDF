import React, { useCallback, useEffect, useState } from "react";
import {
  Alert,
  FileInput,
  Group,
  Paper,
  PasswordInput,
  Radio,
  Stack,
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Button } from "@app/ui/Button";
import { ActionIcon } from "@app/ui/ActionIcon";
import LocalIcon from "@app/components/shared/LocalIcon";
import apiClient from "@app/services/apiClient";

interface ServerCertificate {
  id: string;
  name: string;
  subject: string | null;
  issuer: string | null;
  validFrom: string | null;
  validTo: string | null;
  isDefault?: boolean;
}

const formatDate = (value: string | null): string => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleDateString();
};

/**
 * Blasai fork: manage signing certificates stored on the server. Uploading a
 * .p12/.pfx here lets the "Server" signing mode sign PDFs without re-entering
 * the password each time.
 */
export default function CertificatesSection() {
  const { t } = useTranslation();

  const [certificates, setCertificates] = useState<ServerCertificate[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [uploading, setUploading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await apiClient.get<ServerCertificate[]>(
        "/api/v1/certificates",
      );
      setCertificates(response.data ?? []);
    } catch (e) {
      setError(
        t(
          "settings.certificates.loadError",
          "Could not load stored certificates.",
        ),
      );
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  const handleUpload = async () => {
    if (!file || !password) return;
    setUploading(true);
    setError(null);
    try {
      const formData = new FormData();
      formData.append("file", file);
      formData.append("password", password);
      if (name.trim()) formData.append("name", name.trim());
      await apiClient.post("/api/v1/certificates", formData);
      setFile(null);
      setName("");
      setPassword("");
      await load();
    } catch (e) {
      setError(
        t(
          "settings.certificates.uploadError",
          "Could not add the certificate. Check the file and password.",
        ),
      );
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async (id: string) => {
    setError(null);
    try {
      await apiClient.delete(`/api/v1/certificates/${id}`);
      await load();
    } catch (e) {
      setError(
        t("settings.certificates.deleteError", "Could not delete the certificate."),
      );
    }
  };

  const handleSetDefault = async (id: string) => {
    setError(null);
    try {
      await apiClient.put(`/api/v1/certificates/${id}/default`);
      await load();
    } catch (e) {
      setError(
        t(
          "settings.certificates.defaultError",
          "Could not set the active certificate.",
        ),
      );
    }
  };

  return (
    <Stack gap="lg">
      <div>
        <Title order={3}>
          {t("settings.certificates.title", "Signing certificates")}
        </Title>
        <Text size="sm" c="dimmed">
          {t(
            "settings.certificates.description",
            "Store certificates on the server to sign PDFs without re-entering the password each time. Pick the active one used by the \"Server\" signing mode.",
          )}
        </Text>
      </div>

      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}

      <Paper withBorder p="md" radius="md">
        <Title order={5} mb="xs">
          {t("settings.certificates.addTitle", "Add a certificate")}
        </Title>
        <Stack gap="sm">
          <FileInput
            label={t("settings.certificates.file", "Certificate file (.p12 / .pfx)")}
            placeholder={t("settings.certificates.filePlaceholder", "Choose a file")}
            accept=".p12,.pfx"
            value={file}
            onChange={setFile}
            clearable
          />
          <PasswordInput
            label={t("settings.certificates.password", "Password")}
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
          />
          <TextInput
            label={t("settings.certificates.name", "Display name (optional)")}
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
          />
          <Group justify="flex-end">
            <Button
              onClick={handleUpload}
              loading={uploading}
              disabled={!file || !password}
            >
              {t("settings.certificates.add", "Add certificate")}
            </Button>
          </Group>
        </Stack>
      </Paper>

      <div>
        <Title order={5} mb="xs">
          {t("settings.certificates.storedTitle", "Stored certificates")}
        </Title>
        {loading ? (
          <Text size="sm" c="dimmed">
            {t("settings.certificates.loading", "Loading…")}
          </Text>
        ) : certificates.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t("settings.certificates.empty", "No certificates stored yet.")}
          </Text>
        ) : (
          <Radio.Group
            value={certificates.find((c) => c.isDefault)?.id ?? ""}
            onChange={handleSetDefault}
            label={t("settings.certificates.active", "Active certificate")}
          >
            <Stack gap="sm" mt="xs">
              {certificates.map((cert) => (
                <Paper key={cert.id} withBorder p="sm" radius="md">
                  <Group justify="space-between" wrap="nowrap" align="flex-start">
                    <Group align="flex-start" wrap="nowrap" gap="sm">
                      <Radio value={cert.id} mt={4} />
                      <div>
                        <Text fw={600}>{cert.name}</Text>
                        {cert.subject && (
                          <Text size="xs" c="dimmed">
                            {cert.subject}
                          </Text>
                        )}
                        <Text size="xs" c="dimmed">
                          {t("settings.certificates.validity", "Valid")}:{" "}
                          {formatDate(cert.validFrom)} → {formatDate(cert.validTo)}
                        </Text>
                      </div>
                    </Group>
                    <ActionIcon
                      variant="quiet"
                      accent="danger"
                      aria-label={t("settings.certificates.delete", "Delete")}
                      onClick={() => handleDelete(cert.id)}
                    >
                      <LocalIcon icon="delete-rounded" width="1.25rem" height="1.25rem" />
                    </ActionIcon>
                  </Group>
                </Paper>
              ))}
            </Stack>
          </Radio.Group>
        )}
      </div>
    </Stack>
  );
}
