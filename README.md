# github-bugbounty-codeql-toctou-20260917

Owned, non-destructive GitHub Bug Bounty canary. No third-party repositories,
data, or services are targeted.

The manually dispatched workflow runs a benign CodeQL query and records only
the evaluator's own off-heap arena and executable-map layout. It does not use
a malformed database or inspect another tenant.
