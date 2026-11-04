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
Full-host validation and independent review are recorded separately after they
complete. No SDK, Android device or physical-server qualification is inferred.
