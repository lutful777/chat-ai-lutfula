import { extractImage } from './search.js';
import assert from 'assert';

// Tests for image extraction
// 1. artikel dengan og:image
const html1 = '<meta property="og:image" content="https://example.com/image.jpg">';
assert.strictEqual(extractImage(html1, {}), "https://example.com/image.jpg");

// 2. artikel tanpa gambar
const html2 = '<html><body>hello</body></html>';
assert.strictEqual(extractImage(html2, {}), null);

// 3. URL gambar rusak (invalid url format)
const html3 = '<meta property="og:image" content="not-a-url">';
assert.strictEqual(extractImage(html3, {}), null); // It should return null now since "" is falsy

// 4. Logo/favicon ditolak
const html4 = '<meta property="og:image" content="https://example.com/favicon.ico">';
assert.strictEqual(extractImage(html4, {}), null);

console.log("All API tests passed!");
