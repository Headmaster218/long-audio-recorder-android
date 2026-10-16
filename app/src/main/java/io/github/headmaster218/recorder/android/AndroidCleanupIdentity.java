package io.github.headmaster218.recorder.android;
import android.system.Os;
import android.system.StructStat;
import android.system.ErrnoException;
import java.io.IOException;
import java.nio.file.Path;
import io.github.headmaster218.recorder.core.VerifiedLocalCleanup;
/** Public Android APIs: lstat API21, nanosecond st_ctim API27, below this app's min29. */
final class AndroidCleanupIdentity implements VerifiedLocalCleanup.ChangeIdentity {
    @Override public String read(Path path) throws IOException {
        try {
            StructStat stat=Os.lstat(path.toString());
            if(stat.st_ctim==null || stat.st_ctim.tv_nsec<0 || stat.st_ctim.tv_nsec>=1000000000L) throw new IOException("Strong local change identity unavailable");
            return stat.st_dev+":"+stat.st_ino+":"+stat.st_ctim.tv_sec+":"+stat.st_ctim.tv_nsec;
        } catch(ErrnoException e) { throw new IOException("Local identity could not be read",e); }
    }
}
