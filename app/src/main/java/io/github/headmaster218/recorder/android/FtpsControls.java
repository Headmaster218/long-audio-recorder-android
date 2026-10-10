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

/** Explicit native selection/consent UI. No automatic enrollment of recordings. Password view never saves state. */
final class FtpsControls {
    private static final int LAN_REQUEST = 6201;
    private final Activity activity;
    private final FtpsCoordinator coordinator;
    private final EditText host, port, user, directory, password;
    private final CheckBox remember;
    private final TextView savedProfile, status, receipt;
    private final Spinner sources, queued;
    private final Button save, load, next, add, upload, pause;
    private CommittedSegments.Page displayed;
    private FtpsCoordinator.Snapshot rendered;
    private String shownRevision;
    private boolean initialProfileLoaded, requestingLan;
    private String localNotice = "";
    FtpsControls(Activity activity,LinearLayout column) {
        this.activity = activity; coordinator = FtpsCoordinator.get(activity);
        coordinator.reschedule(); // Re-open repairs retained intent; never enrolls new recordings.
        text(column,"Explicit FTPS transfer",22);
        text(column,"Automatic enrollment: OFF. Select each completed WAV and confirm its saved destination. Only those selected files can transfer in the background. FTPS sends the WAV plus technical segment metadata (capture/run identity, format, input route, timing/sequence and hashes). All originals remain. Cache full still stops recording and alerts; nothing is evicted.",15);
        text(column,"Requires charging + unmetered Wi-Fi. Cellular, VPN and ambiguous routes are blocked, including Upload queued now. TLS 1.2+ and trusted hostname certificates are mandatory on control and data. The server must support explicit FTPS, EPSV, protected data connections, new directories and readback. No plain FTP, SMB or active mode.",14);
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
        text(column,"VERIFIED AT TIME means remote readback matched then. It does not prove server persistence, future integrity or deletion eligibility. Interrupted attempts are checked by readback only, up to three times with backoff. Incomplete or conflicting attempts stay blocked; this version never replaces, overwrites or removes remote attempts. Queue/journal behavior, background lifecycle and power-loss durability still need real-device testing.",14);
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
                + "\n\nThe WAV and technical segment metadata may transfer in the background when charging on unmetered Wi-Fi. A new attempt directory will be created under this saved directory. All originals remain. No other recording is enrolled.")
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
        boolean available = !s.busy && !coordinator.commandPending();
        save.setEnabled(available); load.setEnabled(available); next.setEnabled(available && displayed != null && displayed.hasMore);
        add.setEnabled(available && s.profile != null && displayed != null && !displayed.entries.isEmpty()); sources.setEnabled(available);
        upload.setEnabled(available && !s.items.isEmpty()); pause.setEnabled(!s.paused || s.busy);
        String pageInfo = displayed == null ? "" : "\nCompleted page: " + displayed.entries.size() + "; rejected entries: " + displayed.rejected;
        status.setText((localNotice.isEmpty() ? "" : localNotice + "\n") + s.notice + "\nQueue records: " + s.items.size()
            + (s.paused ? " · PAUSED" : "") + pageInfo);
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
            + "\nOriginals retained. No deletion authorization.");
    }
}
