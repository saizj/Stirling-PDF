import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Group, Modal, NumberInput } from "@mantine/core";
import { Button } from "@editor/ui/Button";

interface MovePageModalProps {
  opened: boolean;
  /** 1-based position of the page within its track. */
  position: number;
  pageCount: number;
  onClose: () => void;
  /** Called with the clamped 1-based target position; never with `position`. */
  onMove: (targetPosition: number) => void;
}

/**
 * Blasai fork: moves a page to a typed position. Dragging is unreliable on
 * touch screens and slow across long documents.
 */
export function MovePageModal({
  opened,
  position,
  pageCount,
  onClose,
  onMove,
}: MovePageModalProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<number | string>(position);

  useEffect(() => {
    if (opened) setDraft(position);
  }, [opened, position]);

  const commit = () => {
    const parsed = typeof draft === "number" ? draft : parseInt(draft, 10);
    onClose();
    if (Number.isNaN(parsed)) return;
    const target = Math.min(Math.max(parsed, 1), pageCount);
    if (target !== position) onMove(target);
  };

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={t("pageTracks.movePage.title", "Move to page")}
      centered
      size="xs"
    >
      <NumberInput
        label={t(
          "pageTracks.movePage.current",
          "Current page: {{position}} of {{count}}",
          { position, count: pageCount },
        )}
        value={draft}
        onChange={setDraft}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            commit();
          }
        }}
        min={1}
        max={pageCount}
        clampBehavior="strict"
        data-autofocus
      />
      <Group justify="flex-end" mt="md">
        <Button variant="tertiary" onClick={onClose}>
          {t("cancel", "Cancel")}
        </Button>
        <Button variant="primary" onClick={commit}>
          {t("pageTracks.movePage.confirm", "Move")}
        </Button>
      </Group>
    </Modal>
  );
}
