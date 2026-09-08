import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Test config is kept separate from vite.config.js so the production build stays
// untouched. CI runs `vitest run --coverage` and ingests coverage/lcov.info.
export default defineConfig({
    plugins: [react()],
    test: {
        environment: 'jsdom',
        globals: true,
        setupFiles: './src/test/setup.js',
        // Some suites render whole dashboards; 5s is not enough for those under load.
        testTimeout: 20000,
        hookTimeout: 20000,
        // Several suites render entire dashboards, and unbounded workers starve each other
        // badly enough that findBy* queries time out on machines with fewer cores than vitest
        // assumes. Bounded parallelism costs a few seconds and removes the flakiness.
        // Vitest 4 removed test.poolOptions and promoted these to top-level options; the old
        // form was silently ignored, so the bound was inert and the flakiness came back.
        maxWorkers: 4,
        minWorkers: 1,
        css: false,
        coverage: {
            provider: 'v8',
            // 'lcovonly' (not 'lcov') so no HTML report dir is generated — the
            // istanbul HTML assets (sorter.js) trip a CodeQL DOM-XSS finding.
            reporter: ['text', 'lcovonly', 'json-summary'],
            reportsDirectory: './coverage',
        },
    },
})
