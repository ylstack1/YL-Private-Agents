feat(web-mode): fix login form submit - async safe function

Fix web mode login not responding:
- Made safe() async function so await works properly
- Rewrote login form submit handler with async/await
- Added Enter key handler using dispatchEvent(new Event('submit'))
- Added better error messages for connection failures

Copied webaccess/ and assets/web-access/ carry YL-COS AGPL-3.0 provenance (private use only).