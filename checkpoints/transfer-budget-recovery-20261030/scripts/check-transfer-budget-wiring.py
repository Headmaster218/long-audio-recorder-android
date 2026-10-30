#!/usr/bin/env python3
"""Narrow source contracts, not Android runtime or SQL bytecode proof."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'app/src/main/java/io/github/headmaster218/recorder'
q=(root/'android/FtpsQueue.java').read_text();c=(root/'android/FtpsCoordinator.java').read_text();u=(root/'android/FtpsControls.java').read_text();checks=0
def check(value):
 global checks
 assert value
 checks+=1
def section(s,start,end):return s[s.index(start):s.index(end,s.index(start))]
check('getAbsolutePath(), null, 6)' in q)
upgrade=section(q,'@Override public void onUpgrade','private static void createBudget')
check('oldVersion > 5 || newVersion != 6' in upgrade);check('if (oldVersion < 5)' in upgrade);check(upgrade.count('createBudget(db)')==1)
reserve=section(q,'boolean reserveBudget(','private static void createHistory')
check(reserve.index('db.beginTransaction()')<reserve.index('readBudget(db)')<reserve.index('if(!before.enabled)'))
check(reserve.index('if(!before.enabled)')<reserve.index('TransferBudget.estimate')<reserve.index('TransferBudget.reserve'))
check('if(after==null) { db.setTransactionSuccessful(); return false; }' in reserve)
check(reserve.index('writeBudget(db,before,after)')<reserve.index('db.setTransactionSuccessful();return true;')<reserve.index('db.endTransaction()'))
check('id=1 AND revision=?' in q)
for start,end,ctor in [('private boolean recheckDeferred','private char[] password','new FtpsNetwork(context,network)'),('private void runOne','private boolean triggered','new FtpsNetwork(context,selectedNetwork)')]:
 s=section(c,start,end)
 check(s.count('queue.reserveBudget(')==1)
 check(s.index('FtpsNetwork.allowed(')<s.index('queue.reserveBudget(')<s.index(ctor)<s.index('FtpsTransfer.run('))
 check('entry.metadata.wavBytes,entry.metadata.encode().length' in s)
 check('300000' in s)
 branch=s.split('if(!queue.reserveBudget(',1)[1].split('\n            }',1)[0]
 check('return true;' in branch or 'return;' in branch)
check(c.count('new FtpsNetwork(')==2)
run=section(c,'private void runOne','private boolean triggered')
check(run.index('queue.reserveBudget(')<run.index('queue.begin(item,attempt)')<run.index('new FtpsNetwork('))
check('if(!queue.reserveBudget(' in run and 'Upload now does not bypass it.' in run)
save=section(c,'boolean saveTransferBudget','void uploadNow')
check('return command(new Command()' in save);check('if(activeJob!=null)' in save)
check(save.index('if(activeJob!=null)')<save.index('queue.configureBudget'))
check('expectedRevision,reset,false' in save)
check('updated.transferBudget = queue.transferBudget()' in c);check('snapshot.transferBudget = old.transferBudget' in c)
check('coordinator.saveTransferBudget(enabled,limit,expected.revision,reset)' in u)
check('final boolean enabled=reset ? expected.enabled' in u);check('final long limit=reset ? expected.limitBytes' in u)
check('resetBudget.setEnabled(available && s.transferBudget!=null)' in u)
check('not a carrier' in u.lower() and 'counter never resets by date' in u)
check('initialBudgetLoaded' in u and 'setSaveEnabled(false)' in u)
print('Transfer-budget narrow source contracts passed:',checks,'(no Android execution)')
