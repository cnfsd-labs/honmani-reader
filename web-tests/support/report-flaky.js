// Prints a GitHub Actions warning for every test that failed and then passed on a retry, from
// Playwright's JSON report: node support/report-flaky.js test-results/results.json
const fs = require('fs');

const file = process.argv[2];
if (!file || !fs.existsSync(file)) {
  console.log(`No report at ${file}; nothing to check for flaky tests.`);
  process.exit(0);
}

const flaky = [];
function walk(suite, titles) {
  const here = suite.title && !suite.file ? [...titles, suite.title] : titles;
  for (const spec of suite.specs || []) {
    if ((spec.tests || []).some((test) => test.status === 'flaky')) {
      flaky.push(`${spec.file}:${spec.line} ${[...here, spec.title].join(' > ')}`);
    }
  }
  for (const child of suite.suites || []) walk(child, here);
}
for (const suite of JSON.parse(fs.readFileSync(file, 'utf8')).suites || []) walk(suite, []);

for (const name of flaky) console.log(`::warning title=Flaky browser test::${name}`);
console.log(flaky.length ? `${flaky.length} flaky test(s) passed only on a retry.` : 'No flaky tests.');
