package io.github.headmaster218.recorder.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import io.github.headmaster218.recorder.core.CommittedSegments;
import io.github.headmaster218.recorder.core.ExportSession;
import io.github.headmaster218.recorder.core.ExportPickerTicket;

/** Native SAF controls appended to the existing screen. No microphone/settings lifecycle changes. */
final class ExportControls {
    private final Activity activity;
    private final ExportCoordinator coordinator;
    private final TextView status;
    private final Spinner segments;
    private final Button first, next, save, cancel;
    private CommittedSegments.Page displayed;
    private final ExportPickerTicket ticket;
    private String localNotice = "";
    ExportControls(Activity activity, LinearLayout column, Bundle saved) {
        this.activity = activity; coordinator = ExportCoordinator.get(activity);
        TextView explanation = new TextView(activity);
        explanation.setText("Export a completed WAV\nChoose a segment, then a destination in Android’s file picker. A cloud provider may upload it. All originals remain private and are kept. Incomplete segments cannot be exported. Keep this app open until verification ends.");
        explanation.setPadding(0,24,0,10); column.addView(explanation);
        first = button(column, "Load / refresh first page", new View.OnClickListener() { @Override public void onClick(View v) { localNotice = ""; coordinator.load(null); render(); } });
        segments = new Spinner(activity); column.addView(segments);
        next = button(column, "Next page", new View.OnClickListener() { @Override public void onClick(View v) {
            ExportSession.Snapshot s = coordinator.session.snapshot();
            if (s.page != null && s.page.hasMore) { localNotice = ""; coordinator.load(s.page.cursor()); render(); }
        } });
        save = button(column, "Save selected WAV…", new View.OnClickListener() { @Override public void onClick(View v) { choose(); } });
        cancel = button(column, "Cancel export", new View.OnClickListener() { @Override public void onClick(View v) {
            ExportSession.Snapshot s = coordinator.session.snapshot();
            if (s.phase == ExportSession.Phase.CHOOSING) coordinator.pickerCancelled(s.token, "Selection cancelled; stale picker replies will be ignored. Originals retained.");
            else coordinator.cancel();
            render();
        } });
        status = new TextView(activity); status.setTextIsSelectable(true); column.addView(status);
        ticket = saved == null ? new ExportPickerTicket() : new ExportPickerTicket(saved.getInt("export-picker-code",10000), saved.getString("export-picker-token"));
        ExportSession.Snapshot s = coordinator.session.snapshot();
        if (ticket.token() != null && !ticket.token().equals(s.token)) {
            ticket.forgetToken(); localNotice = "Previous export was interrupted; destination outcome is unknown. Originals are retained. No automatic retry.\n";
        } else if (coordinator.previousInterrupted() && s.phase == ExportSession.Phase.IDLE && s.page == null) {
            localNotice = "Previous export was interrupted; destination may be empty, partial or complete. Originals are retained. Check your destination before retrying.\n";
        }
        render();
    }
    private Button button(LinearLayout p, String label, View.OnClickListener click) {
        Button b = new Button(activity); b.setText(label); b.setOnClickListener(click); p.addView(b); return b;
    }
    void saveState(Bundle out) { out.putInt("export-picker-code", ticket.code()); out.putString("export-picker-token", ticket.token()); }
    private void choose() {
        localNotice = ""; String token = coordinator.choose(segments.getSelectedItemPosition());
        if (token == null) { render(); return; }
        int requestCode = ticket.issue(token);
        if (requestCode < 0) { coordinator.pickerCancelled(token, "Picker request limit reached; close and reopen this screen."); render(); return; }
        CommittedSegments.Entry entry = coordinator.session.snapshot().entry;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/wav").putExtra(Intent.EXTRA_TITLE, entry.suggestedName())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { activity.startActivityForResult(intent, requestCode); }
        catch (RuntimeException e) { coordinator.pickerCancelled(token, "File picker unavailable; export has not started."); ticket.forgetToken(); }
        render();
    }
    void result(int request, int result, Intent data) {
        String token = ticket.consume(request);
        if (token == null) return; // Unrelated, stale or duplicate Activity result; never rebind it to a new selection.
        if (result != Activity.RESULT_OK) coordinator.pickerCancelled(token, "File picker cancelled; no audio was copied. Originals retained.");
        else {
            try {
                Uri uri = data == null ? null : data.getData();
                if (uri == null || !"content".equals(uri.getScheme()) || !DocumentsContract.isDocumentUri(activity, uri))
                    coordinator.pickerCancelled(token, "Invalid document target; no audio was copied. Originals retained.");
                else coordinator.copy(token, uri);
            } catch (RuntimeException e) { coordinator.pickerCancelled(token, "Document target unavailable; no audio was copied. Originals retained."); }
        }
        render();
    }
    void render() {
        ExportSession.Snapshot s = coordinator.session.snapshot();
        if (displayed != s.page) {
            displayed = s.page; ArrayList<String> labels = new ArrayList<String>();
            if (displayed != null) for (CommittedSegments.Entry e : displayed.entries) {
                labels.add(e.metadata.intent.epoch.captureId.substring(0, Math.min(8, e.metadata.intent.epoch.captureId.length()))
                    + " · segment " + e.metadata.intent.sequence + " · " + e.metadata.wavBytes + " bytes");
            }
            segments.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, labels));
        }
        boolean available = !s.busy();
        first.setEnabled(available); next.setEnabled(available && s.page != null && s.page.hasMore);
        save.setEnabled(available && s.page != null && !s.page.entries.isEmpty()); segments.setEnabled(available);
        cancel.setEnabled(s.phase == ExportSession.Phase.CHOOSING || (s.phase == ExportSession.Phase.COPYING && !coordinator.session.cancellationRequested()));
        String page = s.page == null ? "" : "\n" + s.page.entries.size() + " completed segments on this page; " + s.page.rejected + " invalid entries encountered and skipped.";
        String cancelText = coordinator.session.cancellationRequested() && s.phase == ExportSession.Phase.COPYING ? "\nCancellation requested; waiting for the provider to release I/O. Do not start another export." : "";
        String receipt = s.result == null || s.result.sha256.isEmpty() ? "" : "\nCopied bytes: " + s.result.bytes + "\nSource SHA-256: " + s.result.sha256;
        status.setText(localNotice + s.phase.name() + ": " + s.detail + cancelText + receipt + page);
    }
}
