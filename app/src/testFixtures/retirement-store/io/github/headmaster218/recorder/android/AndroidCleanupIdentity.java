package io.github.headmaster218.recorder.android;

import io.github.headmaster218.recorder.core.VerifiedLocalCleanup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Host ctime adapter only; production Android Os.lstat is not exercised by this suite. */
final class AndroidCleanupIdentity implements VerifiedLocalCleanup.ChangeIdentity {
    @Override public String read(Path path) throws IOException {
        try {
            Object value = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof FileTime)) throw new IOException("Host ctime unavailable");
            return value.toString();
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IOException("Host ctime unavailable", e);
        }
    }
}
