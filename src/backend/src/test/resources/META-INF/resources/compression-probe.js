// A static file for TokenAnswersAreNotCompressedTest's positive control: served from META-INF/resources the way the web
// bundle is in the release image (ADR 0069), it must come back gzip-encoded to a client that accepts it (ADR 0103).
// Its body is long enough that no size threshold could explain an uncompressed answer away.
export const probe = [
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
  'The application compresses its answers by media type, the proxies do not.',
]
