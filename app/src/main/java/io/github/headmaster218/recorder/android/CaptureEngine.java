package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRouting;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.github.headmaster218.recorder.core.CachePolicy;
import io.github.headmaster218.recorder.core.CaptureTimeline;
import io.github.headmaster218.recorder.core.DirectorySpool;
import io.github.headmaster218.recorder.core.PcmSegmentWriter;
import io.github.headmaster218.recorder.core.PcmReadAccounting;

/** Two bounded workers: AudioRecord never waits for disk/network work. No automatic restart. */
final class CaptureEngine implements Runnable {
    enum Stop { NONE, PAUSE, STOP, INTERRUPTED }
    interface Completion { void finished(CaptureEngine engine,String result,boolean paused,long nextEpoch); }
    private static final int BLOCK_SAMPLES = 2048, BLOCKS = 16;
    private static final class Block {
        final short[] samples = new short[BLOCK_SAMPLES];
        final byte[] bytes = new byte[BLOCK_SAMPLES * 2];
        int count; Snapshot snapshot; boolean uncertain;
    }
    private static final class Snapshot {
        final String route, physicalFormat;
        final boolean silenced, unexpectedRoute;
        Snapshot(String route,String physicalFormat,boolean silenced,boolean unexpectedRoute) {
            this.route = route; this.physicalFormat = physicalFormat; this.silenced = silenced; this.unexpectedRoute = unexpectedRoute;
        }
    }
    private final Context context;
    private final RecordingSettings settings;
    final String captureId;
    private final String runId = UUID.randomUUID().toString();
    private final boolean resumedCapture;
    private final Completion completion;
    private final ArrayBlockingQueue<Block> free = new ArrayBlockingQueue<Block>(BLOCKS);
    private final ArrayBlockingQueue<Block> ready = new ArrayBlockingQueue<Block>(BLOCKS);
    private final AtomicLong configurationEvents = new AtomicLong();
    private final PcmReadAccounting accounting = new PcmReadAccounting();
    private volatile Stop stop = Stop.NONE;
    private volatile String failure;
    private volatile boolean producerDone;
    private long epochNumber;
    CaptureEngine(Context context,RecordingSettings settings,String captureId,long epoch,boolean resumedCapture,Completion completion) {
        this.context = context; this.settings = settings; this.captureId = captureId; this.epochNumber = epoch;
        this.resumedCapture = resumedCapture; this.completion = completion;
        for (int i=0;i<BLOCKS;i++) free.add(new Block());
    }
    synchronized void requestStop(Stop reason) { if (reason == Stop.STOP || stop == Stop.NONE) stop = reason; }
    private synchronized void fail(String message) { if (failure == null) failure = RecorderState.bounded(message); if (stop == Stop.NONE) stop = Stop.INTERRUPTED; }
    @Override public void run() {
        DirectorySpool spool = null; PcmSegmentWriter writer = null; AudioRecord record = null; Thread producer = null;
        Snapshot previous = null;
        try {
            Path root = context.getFilesDir().toPath().resolve("audio-spool");
            spool = new DirectorySpool(root,new CachePolicy(settings.cacheBytes,RecordingSettings.FREE_RESERVE),new AndroidDirectoryProtocol(),new DirectorySpool.Faults() { @Override public void before(DirectorySpool.Operation op,Path path) { } });
            long sequence = spool.nextSequence(captureId);
            if (stop == Stop.NONE) {
                record = createRecord(); final AudioRecord microphone = record;
                producer = new Thread(new Runnable() { @Override public void run() { capture(microphone); } },"recorder-capture"); producer.start();
            } else producerDone = true;
            while (!producerDone || !ready.isEmpty()) {
                Block block = ready.poll(100,TimeUnit.MILLISECONDS); if (block == null) continue;
                try {
                    Snapshot current = block.snapshot;
                    if (writer == null) {
                        CaptureTimeline.Gap first = current.silenced ? CaptureTimeline.Gap.POLICY_SILENCE
                            : resumedCapture ? CaptureTimeline.Gap.RESTART : CaptureTimeline.Gap.START;
                        writer = new PcmSegmentWriter(spool,epoch(current,first),settings.targetFrames,sequence,new PcmSegmentWriter.Listener() { @Override public void published(io.github.headmaster218.recorder.core.SegmentStore.Published published) { } });
                    } else if (block.uncertain || !current.route.equals(previous.route)) {
                        if (epochNumber == Long.MAX_VALUE) throw new IOException("Epoch counter exhausted");
                        epochNumber++;
                        CaptureTimeline.Gap gap = current.silenced ? CaptureTimeline.Gap.POLICY_SILENCE
                            : !current.physicalFormat.equals(previous.physicalFormat) ? CaptureTimeline.Gap.FORMAT_CHANGE
                            : CaptureTimeline.Gap.ROUTE_CHANGE_UNCERTAIN;
                        writer.beginEpoch(epoch(current,gap));
                    }
                    // Explicit little-endian serialization; no native-endian assumptions.
                    for (int i=0;i<block.count;i++) { short value = block.samples[i]; block.bytes[i*2] = (byte) value; block.bytes[i*2+1] = (byte) (value >>> 8); }
                    writer.append(block.bytes,0,block.count*2);
                    if (!accounting.appendConfirmed(block.count)) throw new IOException("PCM acknowledgement counters overflowed");
                    RecorderState.writtenFrames += block.count / settings.channels;
                    RecorderState.route = current.route; previous = current;
                } finally { block.snapshot = null; free.offer(block); }
            }
            if (writer != null) {
                if (failure == null && stop == Stop.STOP) writer.stop(); else writer.interrupt();
            }
        } catch (Exception e) {
            fail("Interrupted: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            requestStop(Stop.INTERRUPTED);
            if (producer != null) {
                // Keep the foreground service visible until the microphone worker releases AudioRecord.
                while (producer.isAlive()) {
                    try { producer.join(1000); } catch (InterruptedException e) { fail("Interrupted while waiting for microphone release"); }
                    if (producer.isAlive()) RecorderState.status = "Stopping: waiting for Android to release the microphone";
                }
            } else if (record != null) record.release();
            if (spool != null) try { spool.close(); } catch (IOException e) { fail("Storage close failed; preserved files need inspection: " + e.getMessage()); }
            PcmReadAccounting.Snapshot finalAccounting = accounting.snapshot();
            if (finalAccounting.hasUnconfirmed() && failure == null) fail("Run ended with unconfirmed PCM; recovery inspection is required");
            boolean paused = failure == null && stop == Stop.PAUSE;
            String result = failure != null ? failure + " Local files are preserved; recording will not restart automatically."
                : paused ? "Paused. Tap Start to resume with a new run and uncertain epoch."
                : "Stopped. Local recordings are preserved. No upload or deletion occurred.";
            // Keep the accounting warning first so bounded status persistence cannot truncate it away.
            result = finalAccounting.warning() + result;
            completion.finished(this,result,paused,epochNumber == Long.MAX_VALUE ? epochNumber : epochNumber + 1);
        }
    }
    private CaptureTimeline.Epoch epoch(Snapshot snapshot,CaptureTimeline.Gap reason) {
        return new CaptureTimeline.Epoch(captureId,runId,epochNumber,settings.format,settings.requestedInput(),snapshot.route,reason);
    }
    private AudioRecord createRecord() throws IOException {
        int mask = settings.channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        int minimum = AudioRecord.getMinBufferSize(settings.rate,mask,AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0 || minimum > 256*1024) throw new IOException("Requested client format/buffer is unsupported");
        int frameBytes = settings.channels * 2;
        int buffer = Math.max(minimum * 2,settings.rate * frameBytes / 5);
        buffer = ((buffer + frameBytes - 1) / frameBytes) * frameBytes;
        AudioRecord r = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(settings.rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(buffer).build();
        try {
            if (r.getState() != AudioRecord.STATE_INITIALIZED || r.getSampleRate() != settings.rate
                || r.getChannelCount() != settings.channels || r.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT)
                throw new IOException("AudioRecord did not accept the exact requested client PCM format");
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo phone = null;
            for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { phone = d; break; }
            if (phone == null || !r.setPreferredDevice(phone)) throw new IOException("Built-in microphone preference is unavailable");
            return r;
        } catch (IOException | RuntimeException e) { r.release(); throw e; }
    }
    private void capture(AudioRecord recorder) {
        AudioRouting.OnRoutingChangedListener routeListener = new AudioRouting.OnRoutingChangedListener() { @Override public void onRoutingChanged(AudioRouting router) { configurationEvents.incrementAndGet(); } };
        AudioManager.AudioRecordingCallback configurationListener = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) { configurationEvents.incrementAndGet(); }
        };
        Block held = null; boolean routesRegistered = false, configRegistered = false;
        long previousVersion = -1, lastData = SystemClock.elapsedRealtime(), lastPermissionCheck = 0;
        try {
            recorder.addOnRoutingChangedListener(routeListener,new Handler(Looper.getMainLooper())); routesRegistered = true;
            recorder.registerAudioRecordingCallback(context.getMainExecutor(),configurationListener); configRegistered = true;
            if (stop != Stop.NONE) return;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not enter recording state");
            RecorderState.status = "Recording visibly; local spool only";
            while (stop == Stop.NONE) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastPermissionCheck >= 1000) {
                    if (!RecordingService.permissionsReady(context)) throw new IOException("Microphone/notification permission or visibility was removed");
                    lastPermissionCheck = now;
                }
                held = free.poll();
                if (held == null) throw new IOException("Bounded storage queue is full; stopped with an unknown capture gap");
                long before = configurationEvents.get();
                int count = recorder.read(held.samples,0,BLOCK_SAMPLES,AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IOException(count == AudioRecord.ERROR_DEAD_OBJECT
                    ? "AudioRecord died; explicit user restart required" : "AudioRecord read failed: " + count);
                if (count == 0) {
                    free.offer(held); held = null;
                    if (now - lastData > 2000) throw new IOException("No PCM frames received for two seconds; capture continuity unknown");
                    Thread.sleep(8); continue;
                }
                if (!accounting.readAccepted(count)) throw new IOException("PCM read counters overflowed; unconfirmed sample count is unknown");
                lastData = now;
                Snapshot snapshot = snapshot(recorder);
                long after = configurationEvents.get();
                held.count = count; held.snapshot = snapshot; held.uncertain = previousVersion >= 0 && (after != previousVersion || before != after);
                previousVersion = after;
                if (!ready.offer(held)) throw new IOException("Bounded writer queue refused PCM; uncertain uncommitted buffer");
                held = null;
                if (count % settings.channels != 0) throw new IOException("Android returned an incomplete interleaved frame; partial bytes preserved for recovery");
                if (snapshot.silenced) throw new IOException("Android reports policy-silenced microphone audio; recording interrupted");
                if (snapshot.unexpectedRoute) throw new IOException("Actual input left the requested built-in microphone; recording interrupted");
            }
        } catch (Exception e) { fail("Microphone interruption: " + e.getMessage()); }
        finally {
            if (held != null) free.offer(held);
            try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
            catch (RuntimeException e) { fail("Android microphone stop failed: " + e.getClass().getSimpleName()); }
            try { if (routesRegistered) recorder.removeOnRoutingChangedListener(routeListener); }
            catch (RuntimeException e) { fail("Routing callback cleanup failed"); }
            try { if (configRegistered) recorder.unregisterAudioRecordingCallback(configurationListener); }
            catch (RuntimeException e) { fail("Recording callback cleanup failed"); }
            try { recorder.release(); } catch (RuntimeException e) { fail("AudioRecord release failed"); }
            producerDone = true;
        }
    }
    private Snapshot snapshot(AudioRecord recorder) throws IOException {
        AudioRecordingConfiguration configuration = recorder.getActiveRecordingConfiguration();
        if (configuration == null) throw new IOException("Active configuration unavailable; just-read buffer is uncommitted and continuity is uncertain");
        AudioFormat client = configuration.getClientFormat();
        if (client.getSampleRate() != settings.rate || client.getChannelCount() != settings.channels
            || client.getEncoding() != AudioFormat.ENCODING_PCM_16BIT)
            throw new IOException("Requested client PCM format changed; just-read buffer cannot be attributed safely");
        AudioDeviceInfo device = recorder.getRoutedDevice();
        if (device == null) device = configuration.getAudioDevice();
        if (device == null) throw new IOException("Actual route unavailable; just-read buffer is uncommitted");
        String name = String.valueOf(device.getProductName()).replace('\n',' ').replace('\r',' ');
        if (name.length() > 80) name = name.substring(0,80);
        String physical = format(configuration.getFormat());
        String route = "observed-route(id=" + device.getId() + ",type=" + device.getType() + ",name=" + name
            + ");client=" + format(client) + ";device=" + physical + ";silenced=" + configuration.isClientSilenced()
            + ";boundary=callback/read-observed,not-sample-exact";
        return new Snapshot(route,physical,configuration.isClientSilenced(),device.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC);
    }
    private static String format(AudioFormat format) { return format.getSampleRate() + "Hz/" + format.getChannelCount() + "ch/encoding=" + format.getEncoding(); }
}
