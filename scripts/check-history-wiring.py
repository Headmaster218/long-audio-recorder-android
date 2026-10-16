#!/usr/bin/env python3
"""Finite source integration contracts, not runtime or durability proof."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
def read(name):return (root/'app/src/main/java/io/github/headmaster218/recorder'/name).read_text()
queue=read('android/FtpsQueue.java');worker=read('android/FtpsCoordinator.java');spool=read('core/DirectorySpool.java')
store=read('android/RetirementStore.java');capture=read('android/CaptureEngine.java')
checks=0
def yes(value):
 global checks
 assert value
 checks+=1
auto=queue[queue.index('void automatic(String'):queue.index('long automaticCapacityAt')]
yes('v.put("auto_revision",revision)' in auto and 'v.put("auto_capacity_at",0)' in auto and auto.count('.update(')==1)
wait=queue[queue.index('void automaticCapacityWait'):queue.index('boolean contains')]
yes(wait.count('id=1 AND auto_revision IS NOT NULL')==2)
published=worker[worker.index('void published('):worker.index('private FtpsCoordinator(')]
yes('prepareCatchup' not in published and 'resumeCapacity' not in published)
yes('if(queue.full()){capacityWait();' in published)
resume=worker[worker.index('private void resumeCapacity'):worker.index('private void prepareCatchup')]
yes('queue.paused()' in resume and 'pauseRequested.get()' in resume and 'queue.automaticRevision()==null' in resume)
yes('queue.automaticCapacityAt()>System.currentTimeMillis()' in resume)
prepare=worker[worker.index('private void prepareCatchup'):worker.index('private void postCatchup')]
yes(prepare.index('if(discovered.hasMore)queue.automaticCapacityWait')<prepare.index('catchup=discovered.ids'))
yes('if(queue.full()||(catchup!=null&&!catchup.isEmpty()))' in resume)
yes('queue.automaticCapacityAt()-now' in worker)
yes('if(queue.automaticCapacityAt()==0){prepareCatchup(); postCatchup();}' in worker)
yes('4096,64,cancellation' in worker)
catchup=worker[worker.index('private void catchupOne'):worker.index('void captureClosed')]
yes('DirectorySpool spool=reservation.owner' in catchup and 'spool.enrollment(id)' in catchup)
retire=worker[worker.index('private void retireHistory'):worker.index('private static boolean cleanupPending')]
yes(retire.index('generation=queue.beginRetirement')<retire.index('RetirementStore.synchronize'))
yes(retire.index('lease.pruneRetired')<retire.index('queue.finishRetirement'))
yes('work.size()<FtpsQueue.MAX_RETIRING' in retire)
yes('new RetirementStore(context)' in capture)
yes('private synchronized long sequenceValue' in spool and 'private synchronized void sequenceFloor' in spool)
yes('eligible.remove' in spool and 'confirmed.remove' in spool and 'usage.retireZero' in spool)
yes('disk==null||disk.generation>meta.generation' in store)
yes('missing committed generation evidence' in store)
print(f'PASS: {checks} history/capacity source contracts; not Android execution')
