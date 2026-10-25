package io.github.headmaster218.recorder.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import io.github.headmaster218.recorder.core.CommittedSegments;
import io.github.headmaster218.recorder.core.FtpsTransfer;
import io.github.headmaster218.recorder.core.UploadThreshold;

/** Explicit native selection/consent UI. Automatic enrollment requires explicit destination-bound consent. Password view never saves state. */
final class FtpsControls {
    private static final int LAN_REQUEST = 6201;
    private final Activity activity;
    private final FtpsCoordinator coordinator;
    private final EditText host, port, user, directory, password, threshold;
    private final CheckBox remember;
    private final TextView savedProfile, status, receipt;
    private final Spinner sources, queued;
    private final Button save, load, next, add, upload, pause, automatic, cleanup, saveThreshold;
    private CommittedSegments.Page displayed;
    private FtpsCoordinator.Snapshot rendered;
    private String shownRevision;
    private boolean initialProfileLoaded, initialThresholdLoaded, requestingLan;
    private String localNotice = "";
    FtpsControls(Activity activity,LinearLayout column) {
        this.activity = activity; coordinator = FtpsCoordinator.get(activity);
        coordinator.reschedule(); // Re-open repairs retained intent; never enrolls new recordings.
        text(column,"Explicit FTPS transfer",22);
        text(column,"Automatic enrollment defaults OFF. Enable explicitly below for existing and future finalized chunks to the shown saved destination, or select individual WAVs. FTPS sends the WAV plus technical segment metadata (capture/run identity, format, input route, timing/sequence and hashes). Originals remain unless separate verified-transfer cleanup is enabled. Cache full still stops recording and alerts; nothing is evicted.",15);
        text(column,"Requires charging + unmetered Wi-Fi. Cellular, VPN and ambiguous routes are blocked, including Upload queued now. TLS 1.2+ and trusted hostname certificates are mandatory on control and data. The server must support explicit FTPS, EPSV, protected data connections, new directories and readback. No plain FTP, SMB or active mode.",14);
        threshold = field(column,"Upload trigger: pending WAV bytes (decimal integer)","",32,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text(column,"Default: 9,600,000 bytes. This is one queue-wide trigger, summed across already authorized QUEUED and RECONCILE WAV rows, including deferred rows and distinct destination copies; metadata and other states do not count. Saving does not enroll recordings, enable enrollment, change destinations, unpause, or clear retry delays. Lowering it may make authorized queued work eligible. Below-threshold tails remain queued.",14);
        saveThreshold = button(column,"Save upload threshold…",new View.OnClickListener() {
            @Override public void onClick(View v) { confirmThreshold(); }
        });
        text(column,"Upload queued now / resume overrides only the byte trigger for currently runnable queued rows and resumes a paused queue. It does not bypass safety gates or retry delays; future rows still need the threshold unless requested again. Already-started attempts use readback-only reconciliation independently of the byte trigger.",14);
        host = field(column,"FTPS hostname or IP (certificate must match)","",253,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(column,"Explicit FTPS port","21",5,InputType.TYPE_CLASS_NUMBER);
        user = field(column,"Username (printable ASCII)","",128,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        directory = field(column,"Existing absolute remote directory","/",512,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password = field(column,"Password (1–256 printable ASCII characters)","",256,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false); password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        remember = new CheckBox(activity); remember.setText("Remember this password: create/use an Android Keystore AES key and keep only encrypted app-private, no-backup credentials. Device unlock/key failures block transfers.");
        remember.setChecked(false); remember.setSaveEnabled(false); column.addView(remember);
        text(column,"Default is session-only. After process death, enter the password again for the same saved destination. Saving a profile alone does not queue any recordings. Existing explicitly queued files for that exact profile can resume after credentials are supplied.",14);
        save = button(column,"Save destination and password",new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        savedProfile = text(column,"No saved FTPS destination",15);
        automatic = button(column,"Enable automatic enrollment…",new View.OnClickListener() { @Override public void onClick(View v) { confirmAutomatic(); } });
        cleanup = button(column,"Enable verified-transfer local cleanup…",new View.OnClickListener() { @Override public void onClick(View v) { confirmCleanup(); } });
        load = button(column,"Load / refresh completed WAVs for FTPS",new View.OnClickListener() { @Override public void onClick(View v) { localNotice = ""; coordinator.load(null); } });
        sources = new Spinner(activity); column.addView(sources);
        next = button(column,"Next completed-WAV page",new View.OnClickListener() { @Override public void onClick(View v) {
            if (displayed != null && displayed.hasMore) coordinator.load(displayed.cursor());
        } });
        add = button(column,"Queue selected WAV to shown saved destination…",new View.OnClickListener() { @Override public void onClick(View v) { confirmQueue(); } });
        upload = button(column,"Upload queued now / resume (hard gates apply)",new View.OnClickListener() { @Override public void onClick(View v) {
            if (ensureLanPermission()) { localNotice = ""; coordinator.uploadNow(); }
        } });
        pause = button(column,"Pause FTPS queue / cancel current transfer",new View.OnClickListener() { @Override public void onClick(View v) {
            coordinator.pause(); localNotice = "Pause requested. Owned sockets are being closed; the pause is saved when the queue worker releases I/O."; render();
        } });
        status = text(column,"",14); status.setTextIsSelectable(true);
        text(column,"Queue records and verification receipts (select one)",15);
        queued = new Spinner(activity); column.addView(queued);
        receipt = text(column,"No explicitly queued files",14); receipt.setTextIsSelectable(true);
        queued.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { receipt(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { receipt(); }
        });
        text(column,"VERIFIED AT TIME means remote readback matched then. It does not prove server persistence or future integrity. The separate cleanup opt-in explicitly accepts this risk; it is not strict deletion eligibility. Interrupted attempts are checked by readback only, up to three times with backoff. Incomplete or conflicting attempts stay blocked; this version never replaces, overwrites or removes remote attempts. Queue/journal behavior, background lifecycle and power-loss durability still need real-device testing.",14);
        render();
    }
    private TextView text(LinearLayout parent,String value,int size) {
        TextView t = new TextView(activity); t.setText(value); t.setTextSize(size); t.setPadding(0,12,0,8); parent.addView(t); return t;
    }
    private EditText field(LinearLayout parent,String label,String value,int maximum,int type) {
        text(parent,label,15); EditText e = new EditText(activity); e.setSingleLine(true); e.setInputType(type);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)}); e.setText(value); e.setSaveEnabled(false); parent.addView(e); return e;
    }
    private Button button(LinearLayout parent,String label,View.OnClickListener listener) {
        Button b = new Button(activity); b.setText(label); b.setOnClickListener(listener); parent.addView(b); return b;
    }
    private void save() {
        try {
            FtpsTransfer.Profile profile = new FtpsTransfer.Profile(host.getText().toString().trim(),Integer.parseInt(port.getText().toString()),
                user.getText().toString(),directory.getText().toString());
            if (password.length() < 1 || password.length() > 256) throw new IllegalArgumentException("Enter a password of 1–256 printable ASCII characters.");
            for (int i = 0; i < password.length(); i++) if (password.getText().charAt(i) < 32 || password.getText().charAt(i) > 126)
                throw new IllegalArgumentException("This FTPS version accepts printable ASCII passwords only.");
            char[] secret = new char[password.length()]; password.getText().getChars(0,password.length(),secret,0);
            coordinator.saveProfile(profile,secret,remember.isChecked()); password.getText().clear(); remember.setChecked(false);
            localNotice = "Saving the shown destination. Queueing uses the saved destination label below, not unsaved field edits.";
        } catch (IllegalArgumentException e) { localNotice = e.getMessage(); }
        render();
    }
    private boolean ensureLanPermission() {
        if (FtpsNetwork.permissionReady(activity)) return true;
        localNotice = "Android local-network permission is required for a transfer. Grant it, then tap Queue or Upload again. No transfer is started by the permission response.";
        if (!requestingLan) {
            requestingLan = true;
            try { activity.requestPermissions(new String[]{FtpsNetwork.LAN_PERMISSION},LAN_REQUEST); }
            catch (RuntimeException e) { requestingLan = false; localNotice = "Android could not request local-network permission. Transfer remains blocked."; }
        }
        render(); return false;
    }
    void permissionResult(int request) {
        if (request != LAN_REQUEST) return; requestingLan = false;
        localNotice = FtpsNetwork.permissionReady(activity) ? "Permission granted. Review the saved destination and tap Queue or Upload again."
            : "Local-network permission was not granted. FTPS remains blocked; recording controls are independent.";
        render();
    }
    private void confirmThreshold() {
        try {
            final long bytes = UploadThreshold.parse(threshold.getText().toString());
            final long expected = coordinator.snapshot().uploadThresholdBytes;
            if (expected <= 0) { localNotice = "Wait for valid saved transfer settings before saving."; render(); return; }
            new AlertDialog.Builder(activity).setTitle("Change queued upload trigger?")
                .setMessage("Save " + bytes + " pending WAV bytes as the queue-wide trigger? Current value: " + expected
                    + ". Lowering it may allow already authorized queued work to run. Charging, unmetered Wi-Fi, permission, exact saved destinations, credentials and trusted TLS remain mandatory. This does not enable enrollment, unpause transfers, clear retry delays or authorize deletion.")
                .setNegativeButton("Cancel",null).setPositiveButton("Save threshold",new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog,int which) {
                        localNotice = coordinator.saveUploadThreshold(bytes,expected)
                            ? "Saving upload threshold; check the saved value below."
                            : "Transfer worker is busy. Threshold was not submitted; try again.";
                        render();
                    }
                }).show();
        } catch (IllegalArgumentException invalid) { localNotice = invalid.getMessage(); render(); }
    }
    private void confirmCleanup() {
        final FtpsCoordinator.Snapshot s=coordinator.snapshot();
        if(s.cleanupRevision!=null) { coordinator.disableCleanup(); localNotice="Cleanup disable requested; new purge authorization is revoked immediately, durable setting will follow."; render(); return; }
        if(s.profile==null) return;
        final String revision=s.profile.revision;
        new AlertDialog.Builder(activity).setTitle("Delete local audio after verified transfer?")
            .setMessage("Destination: " + s.profile.label() + "\n\nFor future successful transfers to this exact destination, the app may permanently remove the finalized local WAV and metadata after fresh authenticated full remote readback, hash/size checks and a durable local receipt. Existing verified rows are not retroactively deleted. Cleanup can reclaim already-finalized chunks while recording continues. It never removes the active chunk. Slow transfers or storage can still fill the cache and stop recording; pending work or bounded fragmented history can still stop enrollment. Export picker/copy defers cleanup. Stopping waits for outstanding filesystem operations before releasing storage ownership. Partial, changed and unverified sources are excluded.\n\nFTPS cannot prove future remote persistence or prevent server changes/deletion. If the remote copy later disappears, your recording may be unrecoverable. This is a weaker readback-at-time policy, not immutable storage or a backup guarantee.\n\nCleanup uses a recoverable quarantine before purge intent; an already committed partial purge may finish after restart. Disable prevents new cleanup, but cannot restore already deleted audio. Empty ordinal markers and receipts remain. Enable only if you accept this risk.")
            .setNegativeButton("Cancel",null).setPositiveButton("Enable local deletion for this destination",new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog,int which) { coordinator.cleanupEnabled(true,revision); }
            }).show();
    }
    private void confirmAutomatic() {
        final FtpsCoordinator.Snapshot s = coordinator.snapshot();
        if (s.automaticRevision != null) { coordinator.automatic(false,null); return; }
        if (s.profile == null || !ensureLanPermission()) return;
        final String revision = s.profile.revision;
        new AlertDialog.Builder(activity).setTitle("Automatically enroll completed recordings?")
            .setMessage("Destination: " + s.profile.label() + "\n\nExisting and future finalized WAV chunks plus technical metadata (capture/run identity, format, input route, timing/sequence and hashes) may transfer in the background. This destination stays pinned even if you save another profile. Charging, unmetered Wi-Fi, permission and trusted TLS remain required. Transfer threshold: " + s.uploadThresholdBytes + " pending WAV bytes; a smaller tail needs Upload queued now.\n\nOriginals remain unless separate verified-transfer cleanup is enabled. The live/detailed queue is capped at 500 rows. Fully cleaned terminal receipts can compact into exact bounded history; fragmented history or pending work can still reach a safe cap. Capacity pressure keeps consent and retries bounded discovery about every five minutes; other validation or discovery-bound failures can turn enrollment off. Previously disabled consent is never re-enabled automatically. Historical catch-up uses pages of up to 64 names and can confirm finalized chunks during recording; more than 4096 spool entries still stops discovery safely. This does not start the microphone or save credentials. Disable stops new admission; one already-admitted row may finish. Pause stops already queued transfers.")
            .setNegativeButton("Cancel",null).setPositiveButton("Enable for this destination",new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog,int which) { localNotice = ""; coordinator.automatic(true,revision); }
            }).show();
    }
    private void confirmQueue() {
        if (!ensureLanPermission()) return;
        final CommittedSegments.Page selectedPage = displayed;
        final int index = sources.getSelectedItemPosition();
        final FtpsCoordinator.Snapshot s = coordinator.snapshot();
        if (selectedPage == null || selectedPage != s.page || index < 0 || index >= selectedPage.entries.size()
                || s.profile == null || !s.profile.revision.equals(shownRevision)) {
            localNotice = "The list or destination changed. Review it before queueing."; render(); return;
        }
        final String revision = shownRevision; CommittedSegments.Entry entry = selectedPage.entries.get(index);
        new AlertDialog.Builder(activity).setTitle("Send this completed recording by FTPS?")
            .setMessage(entry.suggestedName() + "\n" + entry.metadata.wavBytes + " WAV bytes\n\nDestination: " + s.profile.label()
                + "\n\nThe WAV and technical segment metadata may transfer in the background when charging on unmetered Wi-Fi. A new attempt directory will be created under this saved directory. Originals remain unless separate verified-transfer cleanup is enabled. No other recording is enrolled.")
            .setNegativeButton("Cancel",null).setPositiveButton("Queue this recording",new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog,int which) { localNotice = ""; coordinator.enqueue(selectedPage,index,revision); }
            }).show();
    }
    void render() {
        FtpsCoordinator.Snapshot s = coordinator.snapshot();
        if (s.profile != null && !initialProfileLoaded) {
            initialProfileLoaded = true; host.setText(s.profile.destination.host); port.setText(String.valueOf(s.profile.destination.port));
            user.setText(s.profile.destination.user); directory.setText(s.profile.destination.directory);
        }
        shownRevision = s.profile == null ? null : s.profile.revision;
        savedProfile.setText(s.profile == null ? "No saved FTPS destination" : "Saved destination used for queueing:\n" + s.profile.label()
            + (s.profile.credential == null ? "\nPassword persistence: session-only" : "\nPassword persistence: encrypted; unlock required"));
        if (displayed != s.page) {
            displayed = s.page; ArrayList<String> labels = new ArrayList<String>();
            if (displayed != null) for (CommittedSegments.Entry entry : displayed.entries)
                labels.add(entry.metadata.intent.epoch.captureId.substring(0,Math.min(8,entry.metadata.intent.epoch.captureId.length()))
                    + " · segment " + entry.metadata.intent.sequence + " · " + entry.metadata.wavBytes + " bytes");
            sources.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,labels));
        }
        if (rendered != s) {
            int selected = queued.getSelectedItemPosition(); long selectedKey = rendered != null && selected >= 0 && selected < rendered.items.size() ? rendered.items.get(selected).key : -1;
            ArrayList<String> labels = new ArrayList<String>(); int restore = 0;
            for (int i = 0; i < s.items.size(); i++) {
                FtpsQueue.Item item = s.items.get(i); labels.add("#" + item.key + " · " + item.state + " · " + item.bytes + " bytes · " + item.revision.substring(0,8));
                if (item.key == selectedKey) restore = i;
            }
            rendered = s; queued.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,labels));
            if (!labels.isEmpty()) queued.setSelection(restore); receipt();
        }
        if (!initialThresholdLoaded && s.uploadThresholdBytes > 0) {
            threshold.setText(Long.toString(s.uploadThresholdBytes)); initialThresholdLoaded = true;
        }
        boolean available = !s.busy && !coordinator.commandPending();
        saveThreshold.setEnabled(available && s.uploadThresholdBytes > 0);
        threshold.setEnabled(available && s.uploadThresholdBytes > 0);
        cleanup.setEnabled(s.cleanupRevision!=null || (available && s.profile!=null));
        cleanup.setText(s.cleanupRevision==null ? "Enable verified-transfer local cleanup…" : "Disable new local cleanup");
        automatic.setEnabled(s.automaticRevision!=null || (available && s.profile!=null));
        automatic.setText(s.automaticRevision == null ? "Enable automatic enrollment…" : "Disable new automatic enrollment");
        save.setEnabled(available); load.setEnabled(available); next.setEnabled(available && displayed != null && displayed.hasMore);
        add.setEnabled(available && s.profile != null && displayed != null && !displayed.entries.isEmpty()); sources.setEnabled(available);
        upload.setEnabled(available && (!s.items.isEmpty() || s.paused)); pause.setEnabled(!s.paused || s.busy);
        String pageInfo = displayed == null ? "" : "\nCompleted page: " + displayed.entries.size() + "; rejected entries: " + displayed.rejected;
        status.setText((localNotice.isEmpty() ? "" : localNotice + "\n") + s.notice + "\nSaved upload threshold: " + (s.uploadThresholdBytes > 0 ? s.uploadThresholdBytes + " pending WAV bytes" : "Unavailable") + "\nCompacted destination receipts: "+s.retiredCount+" (shown destination: "+s.retiredForCurrent+"). Detailed retired receipts are unavailable; exact dedup history remains. "+s.retirementNotice+"\nQueue records: " + s.items.size()
            + (s.paused ? " · PAUSED" : "") + "\nAutomatic enrollment: " + s.automaticNotice + "\nLocal cleanup: " + s.cleanupLabel + pageInfo);
    }
    private void receipt() {
        if (rendered == null) return; int index = queued.getSelectedItemPosition();
        if (index < 0 || index >= rendered.items.size()) { receipt.setText("No explicitly queued files"); return; }
        FtpsQueue.Item item = rendered.items.get(index);
        receipt.setText("#" + item.key + " " + item.state + "\n" + item.detail
            + "\nDestination: " + rendered.destinations.get(item.revision) + "\nSource: " + item.source
            + "\nWAV bytes: " + item.bytes + "\nWAV SHA-256: " + item.wavHash
            + "\nSource metadata SHA-256: " + item.metadataHash
            + "\nRemote attempt directory: " + (item.attempt == null ? "Not started" : item.attempt)
            + "\nReadback retries used: " + item.reconciliations + "/" + FtpsQueue.MAX_RECONCILIATIONS
            + (item.nextAt == 0 ? "" : "\nNext eligible check: " + new java.util.Date(item.nextAt))
            + (item.verifiedAt == 0 ? "" : "\nVerified at: " + new java.util.Date(item.verifiedAt)
                + "\nTransport metadata SHA-256: " + item.resultMetadataHash + "\nMarker SHA-256: " + item.markerHash)
            + "\nLocal cleanup: " + item.cleanupState + " (separate explicit opt-in; remote future persistence unproven)");
    }
}
