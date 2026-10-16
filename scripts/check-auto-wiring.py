#!/usr/bin/env python3
"""Narrow source contracts only; not Android runtime or concurrency execution."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]/'app/src/main/java/io/github/headmaster218/recorder/android'
c=(r/'CaptureEngine.java').read_text();q=(r/'FtpsCoordinator.java').read_text();ui=(r/'FtpsControls.java').read_text()
checks=[
 'try { FtpsCoordinator.get(context).captureClosed(); } catch (RuntimeException ignored)' in c,
 'try { FtpsCoordinator.get(context).published(published); } catch (RuntimeException ignored)' in c,
 'synchronized (SpoolAdmission.LOCK)' in c and 'synchronized (SpoolAdmission.LOCK)' in q,
 'spool.confirmReady(id,new io.github.headmaster218.recorder.core.VerifiedExport.Cancellation()' in q and 'SystemClock.elapsedRealtime() >= deadline || pauseRequested.get()' in q,
 'Arrays.equals(entry.metadata.encode(),published.metadata.encode())' in q,
 'new Semaphore(32)' in q and 'notifications.tryAcquire()' in q and 'notifications.release()' in q,
 '4096,FtpsQueue.MAX_ROWS' in q,
 'if (pauseRequested.get() || queue.paused()) { catchupDeferred = true;' in q,
 '(!s.items.isEmpty() || s.paused)' in ui,
 'current.revision.equals(shownRevision)' in q,
 'catchupDeferred = false; postCatchup(); publish(' in q,
 'catchup != null && !catchup.isEmpty() && !catchupDeferred' in q,
 'queue.automatic(null,reason' in q,
]
assert all(checks),[i for i,x in enumerate(checks) if not x]
print('PASS: %d source wiring contracts (not behavioral tests)' % len(checks))
