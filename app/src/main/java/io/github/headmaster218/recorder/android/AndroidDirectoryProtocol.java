package io.github.headmaster218.recorder.android;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import io.github.headmaster218.recorder.core.DirectorySpool;

/** Android directory force adapter. Any unsupported/failed operation prevents publication. */
final class AndroidDirectoryProtocol implements DirectorySpool.DirectoryProtocol {
    @Override public void syncDirectory(Path directory) throws IOException {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.toString(),OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,0);
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw new IOException("Sync target is not a directory");
            Os.fsync(descriptor);
        } catch (ErrnoException e) {
            throw new DirectorySpool.StorageException(DirectorySpool.Failure.IO_FAILURE,"Android directory sync failed: " + e.errno,e);
        } finally {
            if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException e) { throw new IOException("Directory close failed",e); }
        }
    }
    @Override public void atomicPublish(Path staging,Path target) throws IOException { new DirectorySpool.NioProtocol().atomicPublish(staging,target); }
}
