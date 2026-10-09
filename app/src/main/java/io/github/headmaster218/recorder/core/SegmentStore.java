package io.github.headmaster218.recorder.core;

import java.io.IOException;
import io.github.headmaster218.recorder.core.CaptureTimeline.SegmentManifest;
import io.github.headmaster218.recorder.core.SegmentMetadata.Intent;

/** Single-writer persistence boundary. No method may overwrite/delete existing unverified audio. */
public interface SegmentStore {
    Staging open(Intent intent) throws IOException;
    interface Staging {
        void append(byte[] bytes, int offset, int length) throws IOException;
        /** Patch/force WAV, force metadata, atomically publish, force parent, then validate. */
        Published finish(SegmentManifest manifest) throws IOException;
        /** Close/force as much as possible and retain every staged byte; never marks upload-ready. */
        void preserve() throws IOException;
    }
    final class Published {
        public final String localObjectId;
        public final SegmentMetadata metadata;
        public Published(String localObjectId, SegmentMetadata metadata) {
            this.localObjectId = Checks.text(localObjectId, "localObjectId");
            if (metadata == null) throw new IllegalArgumentException();
            this.metadata = metadata;
        }
    }
}
