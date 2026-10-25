package io.github.headmaster218.recorder.android;

import android.app.Activity;
import android.media.AudioDeviceInfo;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import io.github.headmaster218.recorder.core.InputRoutePolicy;

/** Per-Start selection/consent only; no persisted or restored device authority. */
final class InputControls {
    private final Activity activity;
    private final Spinner choices;
    private final CheckBox fallback;
    private final Button refresh;
    private final List<InputRoutePolicy.Request> requests=new ArrayList<InputRoutePolicy.Request>();
    InputControls(Activity activity,LinearLayout parent) {
        this.activity=activity;
        TextView label=new TextView(activity);
        label.setText("Requested input for next Start. Actual input is verified while recording and saved in segment metadata. Wired/USB input support depends on Android. Bluetooth is not supported in this phase; no call-audio takeover.");
        parent.addView(label);
        choices=new Spinner(activity); choices.setSaveEnabled(false); parent.addView(choices);
        fallback=new CheckBox(activity); fallback.setSaveEnabled(false);
        fallback.setText("Allow phone microphone fallback for this Start if selected wired/USB input disappears (off by default)"); parent.addView(fallback);
        refresh=new Button(activity); refresh.setText("Refresh available inputs (resets selection and fallback)");
        refresh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { reload(); } }); parent.addView(refresh);
        TextView policy=new TextView(activity); policy.setText("Unchecked: selected input only; unavailable or unverifiable input stops capture and requires explicit Start. Fallback stays on phone for the rest of that AudioRecord run. If experimental recovery starts a new run, it checks the original selection again. A route change has an uncertain boundary, not a seamless or sample-exact switch. Save settings does not save input choices."); parent.addView(policy);
        reload();
    }
    private void reload() {
        requests.clear(); fallback.setChecked(false);
        List<String> labels=new ArrayList<String>();
        requests.add(InputRoutePolicy.Request.phone()); labels.add("Phone built-in microphone · selected only");
        for (AudioDeviceInfo device : new InputDevices(activity,null).available()) {
            int kind=InputDevices.kind(device);
            if (kind != InputRoutePolicy.WIRED && kind != InputRoutePolicy.USB) continue;
            requests.add(new InputRoutePolicy.Request(kind,device.getId(),false));
            String name=String.valueOf(device.getProductName()).replace('\n',' ').replace('\r',' ');
            labels.add((kind == InputRoutePolicy.WIRED ? "Wired" : "USB") + " · " + name.substring(0,Math.min(name.length(),80)) + " · id=" + device.getId());
        }
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_item,labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); choices.setAdapter(adapter);
    }
    InputRoutePolicy.Request snapshot() {
        int index=choices.getSelectedItemPosition();
        if (index < 0 || index >= requests.size()) throw new IllegalArgumentException("Refresh and select an available input.");
        InputRoutePolicy.Request choice=requests.get(index);
        return new InputRoutePolicy.Request(choice.kind,choice.id,fallback.isChecked());
    }
    void consumed() { fallback.setChecked(false); }
    void enabled(boolean enabled) { choices.setEnabled(enabled); fallback.setEnabled(enabled); refresh.setEnabled(enabled); }
}
