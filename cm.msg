feat(web-mode): fix login - safe() was not async

Fix web mode login not working:
- safe() used await outside async function - syntax error prevented login
- Made safe() async so async work functions execute properly
- Added better error messages for login failures (empty token, connection failed)
- Added Enter key handler for login form token input

Copied webaccess/ and assets/web-access/ carry YL-COS AGPL-3.0 provenance (private use only).