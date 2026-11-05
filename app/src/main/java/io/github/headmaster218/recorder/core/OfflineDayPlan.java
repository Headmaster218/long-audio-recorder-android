package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Read-only bounded capacity planning, never storage admission or permission to delete. */
public final class OfflineDayPlan {
    public static final long MIB=1024L*1024L, FREE_RESERVE=32*MIB;
    public final long cacheMiB, requiredFreeBytes, existingBytes, observedFreeBytes, segmentCount;
    public final int existingObjects;
    private OfflineDayPlan(long cache,long free,long used,long available,long segments,int objects) {
        cacheMiB=cache;requiredFreeBytes=free;existingBytes=used;observedFreeBytes=available;segmentCount=segments;existingObjects=objects;
    }
    public static OfflineDayPlan calculate(int rate,int channels,int segmentSeconds,long used,long available,int objects) {
        if(rate<8000 || rate>96000 || channels<1 || channels>2 || segmentSeconds<1 || segmentSeconds>3600
                || used<0 || available<0 || objects<0)throw new IllegalArgumentException("Invalid capacity inputs");
        long count=(86400L+segmentSeconds-1)/segmentSeconds;
        // Spare stage and root lock/sentinel entries; existing partials/history retain their slots.
        if(count+objects+4>SpoolUsage.MAX_OBJECTS)
            throw new IllegalArgumentException("24 hours exceeds the spool object limit at this segment duration. Choose longer segments (for example 300 seconds) or review existing local recordings; nothing was deleted.");
        long pcm=(long)rate*channels*2*segmentSeconds;
        long growth=count*(pcm+2L*SegmentMetadata.MAX_BYTES+WavHeader.BYTES)
            +2*DirectorySpool.FINALIZATION_RESERVE+65536+RetirementSentinel.MAX_FILE_BYTES;
        // Extra 5% planning margin for allocation overhead and measurement drift; not a disk reservation.
        growth+=growth/20+(growth%20==0?0:1);
        if(used>Long.MAX_VALUE-growth)throw new IllegalArgumentException("Capacity arithmetic overflow");
        long required=used+growth;
        long mib=required/MIB+(required%MIB==0?0:1);
        if(mib>262144)throw new IllegalArgumentException("24-hour plan exceeds the supported cache maximum; existing recordings remain unchanged.");
        long free=growth+FREE_RESERVE;
        if(available<free)throw new IllegalArgumentException("Not enough currently free storage for 24 hours: need at least "+free+" bytes free (including the 32 MiB reserve), observed "+available+" bytes. Choose a lower rate/mono or free space yourself. Existing recordings are not deleted.");
        return new OfflineDayPlan(Math.max(16,mib),free,used,available,count,objects);
    }
    /** Two bounded read-only inventories. Changed capacity facts require a fresh explicit check. */
    public static OfflineDayPlan inspect(Path filesDirectory,int rate,int channels,int segmentSeconds)throws IOException {
        return inspect(filesDirectory,rate,channels,segmentSeconds,null);
    }
    static OfflineDayPlan inspect(Path filesDirectory,int rate,int channels,int segmentSeconds,Runnable betweenScans)throws IOException {
        if(!Files.isDirectory(filesDirectory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Private files directory unavailable");
        Path root=filesDirectory.resolve("audio-spool");
        long[] before=inventory(root);
        if(betweenScans!=null)betweenScans.run();
        long[] after=inventory(root);
        if(!java.util.Arrays.equals(before,after))throw new IOException("Spool changed during planning. Check again when storage activity settles.");
        return calculate(rate,channels,segmentSeconds,after[0],Files.getFileStore(filesDirectory).getUsableSpace(),(int)after[1]);
    }
    private static long[] inventory(Path root)throws IOException {
        SpoolUsage usage=new SpoolUsage();int objects=0,files=0;
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Spool root is not a regular directory");
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)) {
                for(Path entry:entries) {
                    if(++objects>4096)throw new IOException("Spool object bound exceeded; planning unavailable");
                    BasicFileAttributes a=Files.readAttributes(entry,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);long bytes=0;
                    if(a.isRegularFile()){bytes=a.size();if(++files>32768)throw new IOException("Planning file bound exceeded");}
                    else if(a.isDirectory()) {
                        try(DirectoryStream<Path> children=Files.newDirectoryStream(entry)) {
                            for(Path child:children) {
                                if(++files>32768)throw new IOException("Planning file bound exceeded");
                                BasicFileAttributes childAttributes=Files.readAttributes(child,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                                if(!childAttributes.isRegularFile())throw new IOException("Unexpected nested or linked spool entry; planning unavailable");
                                bytes=add(bytes,childAttributes.size());
                            }
                        }
                    } else throw new IOException("Unexpected linked spool entry; planning unavailable");
                    // Same key/charge semantics as real admission, including duplicate-ordinal rejection.
                    usage.inventory(entry.getFileName().toString(),bytes);
                }
            }
        }
        return new long[]{usage.total(),usage.objects(),files};
    }
    private static long add(long a,long b)throws IOException {
        if(b<0 || a>Long.MAX_VALUE-b)throw new IOException("Storage inventory overflow");return a+b;
    }
}
