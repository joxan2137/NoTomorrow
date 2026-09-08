import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
    // Zod's namespace re-export needs transformation with the current Vite runner.
    server: { deps: { inline: ['zod'] } },
    testTimeout: 30_000,
    hookTimeout: 30_000,
  },
});
