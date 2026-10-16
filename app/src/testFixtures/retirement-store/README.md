# RetirementStore host reconciliation tests

Run `sh scripts/test-retirement-store.sh` from the candidate repository. The runner compiles
the actual production Android `RetirementStore.java` together with all actual production
core classes. It does not copy the reconciliation algorithm into a test implementation.

Only these Android/database boundaries are substituted:

- `android.content.Context`: supplies a host fixture model and application context.
- `FtpsQueue`: explicit in-memory metadata/rows/floors port with observable validation gates.
  Its `validateRetirement` is a configurable accept/reject gate, not the production proof
  algorithm or SQL. Database proof predicates, transactions, interval and floor validation
  require the separate queue SQL/production-database tests. This suite proves when the
  reconciler calls that boundary and what it does after acceptance or rejection.
- `AndroidCleanupIdentity`: uses host `unix:ctime`; Android `Os.lstat` is not executed.

Every admission attempt uses the real `DirectorySpool` constructor and its physical writer
lock. Conflicting live/partial namespaces also exercise reconciliation directly under the
same physical lock, after checking the spool census's earlier admission rejection.
Fresh tiny synthetic PCM recordings pass through real `PcmSegmentWriter`,
`CommittedSegments`, and `VerifiedLocalCleanup` prepare/quarantine/purge operations to
create original empty retired directory inodes with real encoded bindings. Synthetic
capture strings, generated hashes, and fixed test UUIDs contain no personal data.

Coverage includes INITIALIZING replay, READY witness loss, UUID replacement, database
rollback, history/proof boundary failures, the exact eight-generation recovery bound,
generation gaps, absent/replaced/nonempty/conflicting markers, malformed bindings,
pending-row structure, equal-generation already-absent markers, and existing malformed,
exact, or uncertain witness temp files. Pre/post-root-fsync interruptions must throw from
admission, must not return floors or prematurely mark READY, and must sync the same state
again on retry. The post-fsync case models lost acknowledgement after the real host fsync.

Synchronization retains pending rows and markers; actual marker pruning is a separate
operation and is not claimed here. This is production reconciliation ordering with a
modeled database port, not Android SQLite, Android runtime, device durability, hostile
filesystem mutation, or rollback-of-both-stores proof. Temporary fixtures are removed on
exit. The suite is finite, offline, and does not download, use credentials, or publish.
