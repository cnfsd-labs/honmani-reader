// @ts-check
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // One retry on CI only, so a rare timing flake does not fail the run; it is still named
  // (support/report-flaky.js reads the JSON report).
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['json', { outputFile: 'test-results/results.json' }]]
    : 'list',
  use: {
    // A phone-sized Chromium viewport, like the Android WebView the scripts run in.
    ...devices['Pixel 7'],
    trace: 'retain-on-failure',
  },
});
