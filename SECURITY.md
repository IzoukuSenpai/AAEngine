# AAEngine Security Baseline

Security-sensitive areas:

- remote pack imports
- archive extraction
- self-hosted HTTP
- S3/API credentials
- command mechanics
- packet/Netty hooks
- config file paths
- external plugin registrations

Minimum controls:

- SSRF filtering and redirect revalidation
- archive path normalization / Zip Slip prevention
- compressed/decompressed size budgets
- externalized secrets
- command input sanitization
- strict thread boundaries
- safe handler cleanup
- diagnostics secret redaction
- filesystem sandboxing

See `AAENGINE_RECONSTRUCTION.md` for the detailed design.
