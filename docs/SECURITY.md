# SaveNest security review (Milestone 7)

Checklist against spec §14 and the OWASP SSRF Prevention Cheat Sheet. "Test" names the automated test that covers the control.

## URL fetching (SSRF)

| Control | Implementation | Test |
|---|---|---|
| http/https only, ports 80/443 | `HttpSafeFetcher`, `UrlParser` | `HttpSafeFetcherTest`, `UrlClassifierTest` |
| Block loopback/private/link-local/CGNAT/reserved/multicast, IPv4 + IPv6, v4-mapped/NAT64/6to4 | `AddressPolicy` | `AddressPolicyTest` (38) |
| Cloud metadata (169.254.169.254 via link-local; fd00:ec2::254 via the ULA fc00::/7 block) and internal hostnames | `AddressPolicy` | `AddressPolicyTest` |
| Validate **all** resolved addresses; connect only to validated ones (DNS rebinding) | filtering DNS resolver in `HttpSafeFetcher` | `HttpSafeFetcherTest` (mixed DNS) |
| Re-validate every redirect; hop limit | manual redirects | `HttpSafeFetcherTest` |
| Size limits after decompression, timeouts, MIME allowlist | `HttpSafeFetcher` | `HttpSafeFetcherTest` (oversize, gzip bomb, MIME) |
| No cookies/auth/user headers forwarded | `HttpSafeFetcher` | `HttpSafeFetcherTest` (no header leakage) |
| Images: raster only, magic bytes, no SVG; same fetcher for the preview proxy | `ImageSniffer`, `PreviewImageController` | `MetadataEnricherTest` |
| Network egress restriction | deployment | documented in `DEPLOYMENT.md` (not testable in-app) |

## Application

| Control | Test |
|---|---|
| Every `/api/**` endpoint except csrf/login/register/health requires a session; unknown paths denied | `SecurityIntegrationTest.everyApiEndpointRequiresASession`, `unknownPathsAreDenied` |
| CSRF on every state-changing request, including authenticated ones and login | `SecurityIntegrationTest.authenticatedWritesWithoutCsrfAreRejected`, `AuthIntegrationTest` |
| Cross-user read/write/restore/delete/refresh/open/preview return 404 and change nothing | `SecurityIntegrationTest.otherUsersBookmarksAreInvisibleAndImmutable` |
| Foreign tags/collections cannot be edited, merged, attached or filtered on; DB triggers as backstop | `SecurityIntegrationTest.otherUsersTagsAndCollectionsCannotBeUsedOrChanged`, `OrganizeIntegrationTest` |
| Duplicate responses never reveal another user's bookmark | `SecurityIntegrationTest.duplicateResponsesNeverRevealAnotherUsersBookmark` |
| Export contains only the caller's data, `no-store` | `SecurityIntegrationTest.exportContainsOnlyTheCallersData`, `LifecycleIntegrationTest` |
| CSV formula injection neutralised | `ExportCsvTest` |
| Concurrent duplicate saves → exactly one row (partial unique index) | `SecurityIntegrationTest.concurrentDuplicateSavesCreateExactlyOneBookmark` |
| Rejected schemes (`javascript:`, `file:`, `data:`, `ftp:`), embedded credentials, oversize search, bad sort/view/cursor | `SecurityIntegrationTest.dangerousOrMalformedInputIsRejected` |
| SQL injection: parameterised queries; search input reduced to letters/digits | `OrganizeIntegrationTest.searchTreatsQuerySyntaxAsPlainText`, `SecurityIntegrationTest` |
| Extracted/user text returned as JSON data, never HTML; `nosniff` | `SecurityIntegrationTest.extractedTextIsReturnedAsDataNotMarkup`; React escapes on render |
| Security headers (CSP, frame deny, nosniff, no-referrer) and server-generated request IDs | `SecurityIntegrationTest.securityHeadersArePresent`, `clientSuppliedRequestIdIsIgnored` |
| Rate limits (auth, save, classify, refresh, export) | `RateLimiterTest` |
| Passwords bcrypt; hash erased from session principal; generic login errors | `AuthIntegrationTest` |
| Account deletion removes all owned data and sessions; requires password re-auth + `confirm=true` | `AccountDeletionIntegrationTest` |

## Open items / accepted risks

- Rate limiting is per instance (see `DEPLOYMENT.md`).
- Frontend CSP allows `'unsafe-inline'` scripts (needed by Next.js without nonces). Mitigated by React escaping; the only `dangerouslySetInnerHTML` is the constant theme bootstrap script in `app/layout.tsx` (no user or fetched data); nonce-based CSP is a follow-up.
- No dependency-vulnerability scan is wired in yet; run `npm audit` and OWASP `dependency-check` (or Dependabot) before public release.
- Accessibility audit (axe + screen reader) and Playwright E2E still require a browser run.
