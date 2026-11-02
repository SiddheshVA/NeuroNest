// Vitest setup for component tests: jest-dom matchers, DOM cleanup between tests, and the browser APIs
// jsdom does not implement.
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

afterEach(() => {
  cleanup();
});

window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;
