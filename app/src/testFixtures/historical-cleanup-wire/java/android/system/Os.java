package android.system;
import java.io.*;import java.nio.channels.FileChannel;import java.nio.file.*;import java.nio.file.attribute.FileTime;import java.time.Instant;import java.util.*;
/** Test-only Linux/NIO boundary: real no-follow stat identity and real directory force. */
public final class Os {
 private static final class Open {final Path path;final FileChannel channel;final long dev,ino;Open(Path p,FileChannel c,StructStat s){path=p;channel=c;dev=s.st_dev;ino=s.st_ino;}}
 private static final Map<FileDescriptor,Open> open=new IdentityHashMap<>();
 public static StructStat lstat(String name)throws ErrnoException{try{Map<String,Object> a=Files.readAttributes(Paths.get(name),"unix:dev,ino,mode,ctime",LinkOption.NOFOLLOW_LINKS);Instant t=((FileTime)a.get("ctime")).toInstant();return new StructStat(((Number)a.get("dev")).longValue(),((Number)a.get("ino")).longValue(),((Number)a.get("mode")).intValue(),new StructTimespec(t.getEpochSecond(),t.getNano()));}catch(IOException|RuntimeException e){throw new ErrnoException("lstat",5,e);}}
 public static synchronized FileDescriptor open(String name,int flags,int mode)throws ErrnoException{if(flags!=(OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW)||mode!=0)throw new ErrnoException("unsupported open flags",22,null);Path p=Paths.get(name);StructStat s=lstat(name);if(!OsConstants.S_ISDIR(s.st_mode))throw new ErrnoException("not directory",20,null);try{FileChannel channel=FileChannel.open(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);FileDescriptor fd=new FileDescriptor();open.put(fd,new Open(p,channel,s));return fd;}catch(IOException|RuntimeException e){throw new ErrnoException("open",5,e);}}
 private static Open get(FileDescriptor fd)throws ErrnoException{Open value=open.get(fd);if(value==null)throw new ErrnoException("bad fd",9,null);return value;}
 public static synchronized StructStat fstat(FileDescriptor fd)throws ErrnoException{Open value=get(fd);StructStat s=lstat(value.path.toString());if(s.st_dev!=value.dev||s.st_ino!=value.ino)throw new ErrnoException("path replaced while open",5,null);return s;}
 public static synchronized void fsync(FileDescriptor fd)throws ErrnoException{Open value=get(fd);fstat(fd);try{value.channel.force(true);}catch(IOException e){throw new ErrnoException("fsync",5,e);}}
 public static synchronized void close(FileDescriptor fd)throws ErrnoException{Open value=get(fd);open.remove(fd);try{value.channel.close();}catch(IOException e){throw new ErrnoException("close",5,e);}}
 public static synchronized int openCount(){return open.size();}
}
