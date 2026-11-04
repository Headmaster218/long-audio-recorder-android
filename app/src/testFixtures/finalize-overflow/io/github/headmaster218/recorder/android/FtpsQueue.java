package io.github.headmaster218.recorder.android;
import java.util.*;
import io.github.headmaster218.recorder.core.*;
/** Test-only journal boundary. Does not claim Android SQLite persistence coverage. */
final class FtpsQueue {
 String revision="fixture-revision"; String notice="ON"; long capacity; int capacityWrites, enqueueCalls; boolean paused, full;
 final Set<String> rows=new HashSet<String>();
 static final class Profile { final String revision="fixture-revision"; String label(){return "fixture";} }
 static final class Item { String revision; }
 String automaticRevision(){return revision;}
 String automaticNotice(){return notice;}
 void automatic(String r,String n){revision=r;notice=n;capacity=0;}
 long automaticCapacityAt(){return capacity;}
 void automaticCapacityWait(long at){if(revision!=null){capacity=at;capacityWrites++;notice="capacity wait";}}
 void automaticCapacityScanned(){capacity=0;}
 boolean paused(){return paused;}
 void pause(boolean p){paused=p;}
 boolean full(){return full;}
 boolean enqueue(CommittedSegments.Entry entry,String pin,boolean automatic){enqueueCalls++;if(full)throw new AssertionError("enqueue while full");if(!Objects.equals(revision,pin))throw new AssertionError("stale pin");return rows.add(entry.id+"@"+pin);}
 List<Item> items(boolean pending){return Collections.emptyList();}
 Profile current(){return null;}
 Profile profile(String pin){return null;}
 long uploadThresholdBytes(){return 1048576;}
 TransferBudget.State transferBudget(){return null;}
 String cleanupRevision(){return null;}
 String retiredCount(){return "0";}
 String retiredCount(String pin){return "0";}
 List<Item> retiring(){return Collections.emptyList();}
 List<Item> retirementCandidates(){return Collections.emptyList();}
}
