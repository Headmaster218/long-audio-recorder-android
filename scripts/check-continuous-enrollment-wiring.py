#!/usr/bin/env python3
"""Narrow production integration ordering checks, not Android execution."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]/'app/src/main/java/io/github/headmaster218/recorder'
q=(r/'android/FtpsCoordinator.java').read_text();s=(r/'core/DirectorySpool.java').read_text();ui=(r/'android/FtpsControls.java').read_text()
checks=0
def yes(value):
 global checks
 assert value
 checks+=1
prepare=q[q.index('private void prepareCatchup'):q.index('private void postCatchup')]
yes('4096,64,cancellation' in prepare)
yes(prepare.index('if(discovered.hasMore)queue.automaticCapacityWait')<prepare.index('catchup=discovered.ids'))
yes('else queue.automaticCapacityScanned();' in prepare)
yes('if(cancellation.cancelled())throw new IOException' in prepare and 'throw new CatchupDeferred()' in prepare)
resume=q[q.index('private void resumeCapacity'):q.index('private void prepareCatchup')]
yes('catchup!=null&&!catchup.isEmpty()' in resume)
yes('automaticCapacityScanned' not in resume)
catchup=q[q.index('private void catchupOne'):q.index('void captureClosed')]
yes('DirectorySpool spool=reservation.owner;boolean idle=spool==null' in catchup)
yes(catchup.index('spool.enrollment(id)')<catchup.index('lease.confirmEnrollment')<catchup.index('catalog.load(id)')<catchup.index('enrollmentConsent.admit(ticket)')<catchup.index('queue.enqueue'))
yes(catchup.index('queue.enqueue')<catchup.index('reservation.close()'))
yes('lease.checkEnrollment()' in catchup and 'job.deadline' in catchup)
yes('queue.contains(id,revision)' in catchup)
yes('Arrays.equals(entry.metadata.encode(),confirmed.metadata.encode())' in catchup)
yes('active=null; publishing=null; }' in s)
yes(s.index('publishing=target.getFileName().toString()')<s.index('op(Operation.PUBLISH, target)'))
yes('if(source.equals(publishing))throw new MaintenanceBusy();' in s)
yes('if(closing||closed||released||maintenance!=Maintenance.this)return true;' in s)
disable=q[q.index('if(!enabled){'):q.index('final long requestedEpoch')]
yes(disable.index('enrollmentConsent.disable()')<disable.index('worker.execute'))
yes('command(' not in disable)
yes('s.automaticRevision!=null || (available && s.profile!=null)' in ui)
yes('one already-admitted row may finish' in ui)
yes('enrollmentConsent.enable(queue.automaticRevision(),startupEpoch)' in q)
yes('epoch==Long.MAX_VALUE' in q and 'ticket.used=true' in q)
print(f'PASS: {checks} continuous-enrollment integration contracts; not Android execution')
