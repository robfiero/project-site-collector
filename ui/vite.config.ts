import { defineConfig } from 'vitest/config';

export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'node_modules/**',
        'dist/**',
        'coverage/**',
        'src/**/demoData*',
        'src/**/__mocks__/**',
        'src/**/testUtils/**',
        'src/main.tsx',
        'src/models.ts',
        'src/**/*.d.ts'
      ]
    }
  }
});
