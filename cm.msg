feat(web-mode): fix CSP and syntax errors

Fix web mode login page:
- CSP header: added 'unsafe-inline' for script-src and style-src, added cdnjs.cloudflare.com domain
- Fixed async async function syntax error in app.js
- Fixed stream.js syntax error with explicit braces
- Login page should now load without CSP blocking errors

Copied webaccess/ and assets/web-access/ carry YL-COS AGPL-3.0 provenance (private use only).