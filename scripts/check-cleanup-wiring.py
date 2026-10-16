#!/usr/bin/env python3
"""Source-order contracts, not Android runtime/concurrency execution."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]/'app/src/main/java/io/github/headmaster218/recorder'
c=(r/'android/FtpsCoordinator.java').read_text();u=(r/'android/FtpsControls.java').read_text();s=(r/'core/VerifiedLocalCleanup.java').read_text();a=(r/'android/AndroidCleanupIdentity.java').read_text()
e=(r/'android/ExportCoordinator.java').read_text()
checks=[
 'if(RecorderState.active || ExportCoordinator.cleanupBlocked()) throw new CleanupDeferred()' in c,
 c.index('cleanupRevoked.set(true)')<c.index('queue.revokeCleanup()'),
 'cleanup.setEnabled(s.cleanupRevision!=null ||' in u,
 'pauseRequested.get() || cleanupRevoked.get()' in c,
 'stage.equals("PURGING") && !purgeEntered[0]' in c,
 '!(e instanceof CleanupDeferred)' in c,
 'Os.lstat' in a and 'st_ctim.tv_nsec' in a,
 s.index('Stamp d=new Stamp')<s.index('catalog.verify(entry,cancellation)')<s.index('inspect(directory,result,false)'),
 'directory && partiallyPurged' in s,
 'protocol.atomicPublish(target,retired)' in s,
 'queue.cleanup(item,"PURGING",encoded)' in c and 'queue.cleanup(item,"QUARANTINED",encoded)' in c,
 'item.revision,item.attempt,true,new FtpsTransfer.Guard()' in c,
 'item.cleanupState.equals("RESTORE_ONLY")' in c,
 'synchronized(SpoolAdmission.LOCK) { token=session.choose(displayedPage,index); }' in e,
 'queue.otherNeedsSource(item)' in c,
 '(RecorderState.active || ExportCoordinator.cleanupBlocked()) ? 300000 : 5000' in c,
]
assert all(checks),[i for i,v in enumerate(checks) if not v]
print('PASS: %d cleanup source wiring contracts; not behavior tests' % len(checks))
