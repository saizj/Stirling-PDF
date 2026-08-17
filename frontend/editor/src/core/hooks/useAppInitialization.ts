import { useLaunchedFiles } from "@app/hooks/useLaunchedFiles";

/**
 * App initialization hook
 * Core version: picks up files handed over by the operating system
 *
 * This hook is called once when the app starts to allow different builds
 * to perform initialization tasks that require access to contexts like FileContext.
 */
export function useAppInitialization(): void {
  useLaunchedFiles();
}
