# Delivery Python cache integrity

A November 4 review reproduced an exact-source qualification gap: the verifier
accepted unrecorded `scripts/__pycache__/*.pyc` files, while two host test scripts
used Python's normal source loader. An unchecked-hash cache can run different
code despite unchanged recorded source bytes. This concerns local delivery/test
integrity, not a proven Android runtime defect or a signature/authenticity claim.

The verifier now rejects unrecorded caches like other unrecorded script inputs.
The assembly and verification test helpers compile their source bytes directly,
so they neither consume nor generate those caches. Merely using `-B` would not
have fixed cache reads. No files or caches are automatically deleted. If an old
assembly contains caches, prepare a new output directory as documented.

Focused regressions cover ordinary bytecode, symlinked bytecode and an executable
unchecked-hash cache, alongside the existing source/hash/mode/overlay controls.
Source assembly and verification tests pass (6 and 13 cases respectively).
All 42 offline suites passed on local implementation commit
`76d787fcb61a6b3fe1250f50bcb432c6cafaa83a`, whose tree is identical to published
`0542a9b20040d2caa39a64bc5c0692370e42d360`. Verification passed before and after.
Independent tests confirmed that both poisoned helper caches are rejected and
ignored by the source-only loaders, and clean suites create no source-tree caches.
The subsequent test-only commit `83999b5fbd020d1a2ff61c4e8e75b8579b4fe84d`
strengthens the regression by actually executing its harmless cache marker via
Python's ordinary loader before asserting verifier rejection. Its fresh assembly
passed all 19 affected tests and exact-source verification. Production Java and
wire formats are unchanged. No SDK build, real-wire rerun, Android device or
physical-server qualification was performed in this cache-integrity batch.
