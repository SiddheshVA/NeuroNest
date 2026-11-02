// Component tests (Vitest + React Testing Library, jsdom). Separate from vite.config.js, which the production
// build uses unchanged; this only reuses its plugins and path aliases (@/, next/*, @clerk/nextjs shims).
// The pure-logic *.test.ts files keep running under Node's built-in test runner (npm run test:logic).
import { defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config.js";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: "jsdom",
      include: ["src/**/*.test.tsx"],
      setupFiles: ["./src/test/setup.ts"],
      restoreMocks: true,
    },
  }),
);
