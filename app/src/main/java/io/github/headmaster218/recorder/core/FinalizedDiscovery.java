package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.DirectoryStream;
import java.util.ArrayDeque;

/** Names-only bounded catch-up. A candidate is NOT durability or content evidence. */
public final class FinalizedDiscovery {
    public interface Known { boolean contains(String id); }
    private FinalizedDiscovery() { }
    public static final class Page {
        public final ArrayDeque<String> ids;
        public final boolean hasMore;
        Page(ArrayDeque<String> ids,boolean hasMore){this.ids=ids;this.hasMore=hasMore;}
    }
    /** Bounded sorted selection; exact destination membership supplies restart-safe progress. */
    public static Page page(Path root,Known known,int entryLimit,int candidateLimit)throws IOException {
        return page(root,known,entryLimit,candidateLimit,new VerifiedExport.Cancellation(){@Override public boolean cancelled(){return false;}});
    }
    public static Page page(Path root,Known known,int entryLimit,int candidateLimit,VerifiedExport.Cancellation cancellation)throws IOException {
        VerifiedExport.checkCancelled(cancellation);
        if(root==null||known==null||entryLimit<1||candidateLimit<1)throw new IllegalArgumentException();
        java.util.TreeSet<String> selected=new java.util.TreeSet<String>();boolean more=false;
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return new Page(new ArrayDeque<String>(),false);
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe spool root");
        int examined=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){
            for(Path entry:entries){
                VerifiedExport.checkCancelled(cancellation);
                if(++examined>entryLimit)throw new IOException("Discovery entry bound reached");
                String id=entry.getFileName().toString();
                if(!id.matches("segment-[0-9a-f]{64}-(0|[1-9][0-9]*)\\.ready")||known.contains(id))continue;
                selected.add(id);
                if(selected.size()>candidateLimit){selected.pollLast();more=true;}
            }
        }
        return new Page(new ArrayDeque<String>(selected),more);
    }
    public static ArrayDeque<String> candidates(Path root, Known known, int entryLimit, int candidateLimit) throws IOException {
        if (root == null || known == null || entryLimit < 1 || candidateLimit < 1) throw new IllegalArgumentException();
        ArrayDeque<String> result = new ArrayDeque<String>();
        if (!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return result;
        if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe spool root");
        int examined = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (++examined > entryLimit) throw new IOException("Discovery entry bound reached");
                String id = entry.getFileName().toString();
                if (!id.matches("segment-[0-9a-f]{64}-(0|[1-9][0-9]*)\\.ready") || known.contains(id)) continue;
                if (result.size() >= candidateLimit) throw new IOException("Discovery backlog bound reached");
                result.add(id);
            }
        }
        return result;
    }
}
