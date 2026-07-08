import { extractImage } from './search.js';
import assert from 'assert';

// 1. artikel dengan og:image
const html1 = '<meta property="og:image" content="https://example.com/image.jpg">';
assert.strictEqual(extractImage(html1, {}), "https://example.com/image.jpg");

// 2. artikel tanpa gambar
const html2 = '<html><body>hello</body></html>';
assert.strictEqual(extractImage(html2, {}), null);

// 3. URL gambar rusak (invalid url format)
const html3 = '<meta property="og:image" content="not-a-url">';
assert.strictEqual(extractImage(html3, {}), ""); // normalizedUrl returns '' for invalid URL

console.log("All API tests passed!");
