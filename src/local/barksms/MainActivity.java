package local.barksms;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int GREEN = 0xff167459;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private local.barksms.Settings config;
    private QueueDb db;
    private EditText address, groupName;
    private Switch enabled, archive;
    private TextView status, pending, records;
    private Button test;
    private boolean updating;
    private boolean visibleAddress;
    private boolean resumed;
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refresh();
            handler.postDelayed(this, 3000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Also exclude tasks created before an in-place upgrade.
        for (ActivityManager.AppTask task : getSystemService(ActivityManager.class).getAppTasks()) {
            task.setExcludeFromRecents(true);
        }
        config = new local.barksms.Settings(this);
        db = QueueDb.get(this);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(24));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = text("短信到 Bark", 25);
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title);
        status = text("", 15);
        status.setPadding(0, dp(8), 0, dp(24));
        content.addView(status);

        TextView label = text("Bark 推送地址", 14);
        label.setLabelFor(View.generateViewId());
        content.addView(label);
        LinearLayout inputRow = row();
        address = new EditText(this);
        address.setId(label.getLabelFor());
        address.setTextSize(15);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setTransformationMethod(PasswordTransformationMethod.getInstance());
        address.setHint("https://api.day.app/设备Key");
        address.setText(config.endpoint());
        address.setSaveEnabled(false);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        inputRow.addView(address, new LinearLayout.LayoutParams(0, dp(56), 1));
        ImageButton eye = new ImageButton(this);
        eye.setImageResource(android.R.drawable.ic_menu_view);
        eye.setBackgroundColor(Color.TRANSPARENT);
        eye.setContentDescription("显示或隐藏推送地址");
        eye.setTooltipText("显示或隐藏推送地址");
        eye.setOnClickListener(v -> {
            visibleAddress = !visibleAddress;
            address.setTransformationMethod(visibleAddress ? null : PasswordTransformationMethod.getInstance());
            address.setSelection(address.length());
        });
        inputRow.addView(eye, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(inputRow);

        TextView groupLabel = text("分组名称", 14);
        content.addView(groupLabel);
        groupName = new EditText(this);
        groupName.setId(View.generateViewId());
        groupLabel.setLabelFor(groupName.getId());
        groupName.setTextSize(15);
        groupName.setSingleLine(true);
        groupName.setInputType(InputType.TYPE_CLASS_TEXT);
        groupName.setHint("短信");
        groupName.setText(config.group());
        content.addView(groupName, new LinearLayout.LayoutParams(-1, dp(56)));
        archive = new Switch(this);
        archive.setText("保存到 Bark 历史");
        archive.setTextSize(16);
        archive.setChecked(config.archive());
        content.addView(archive, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout actions = row();
        Button save = button("保存设置", android.R.drawable.ic_menu_save);
        save.setOnClickListener(v -> { if (saveSettings()) toast("设置已保存"); });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(52), 1));
        test = button("测试推送", android.R.drawable.ic_menu_send);
        test.setOnClickListener(v -> testPush());
        actions.addView(test, new LinearLayout.LayoutParams(0, dp(52), 1));
        content.addView(actions);
        divider(content);

        enabled = new Switch(this);
        enabled.setText("启用短信转发");
        enabled.setTextSize(17);
        enabled.setMinHeight(dp(60));
        enabled.setOnCheckedChangeListener((view, checked) -> {
            if (updating) return;
            if (!checked) {
                config.setEnabled(false);
                ForwardService.cancelRecovery(this);
                stopService(new Intent(this, ForwardService.class));
                RetryJob.cancel(this);
            } else if (saveSettings()) {
                if (!local.barksms.Settings.hasPermission(this)) {
                    setChecked(false);
                    requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 1);
                } else {
                    enable();
                }
            } else setChecked(false);
            refresh();
        });
        content.addView(enabled, new LinearLayout.LayoutParams(-1, dp(60)));
        pending = text("", 15);
        pending.setPadding(0, dp(4), 0, dp(10));
        content.addView(pending);
        Button retry = button("立即重试", android.R.drawable.ic_popup_sync);
        retry.setOnClickListener(v -> {
            if (!config.enabled()) { toast("请先启用短信转发"); return; }
            ForwardService.start(this, true);
            toast("已请求重试");
        });
        content.addView(retry, new LinearLayout.LayoutParams(-1, dp(50)));
        divider(content);

        LinearLayout system = row();
        Button battery = button("电池设置", android.R.drawable.ic_lock_idle_low_battery);
        battery.setOnClickListener(v -> openBattery());
        system.addView(battery, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button details = button("应用设置", android.R.drawable.ic_menu_preferences);
        details.setOnClickListener(v -> openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        system.addView(details, new LinearLayout.LayoutParams(0, dp(52), 1));
        content.addView(system);
        divider(content);
        TextView recent = text("最近状态", 15);
        recent.setTypeface(null, Typeface.BOLD);
        content.addView(recent);
        records = text("", 13);
        records.setTextColor(0xff60656a);
        records.setPadding(0, dp(12), 0, 0);
        content.addView(records);
        refresh();
    }

    private void enable() {
        if (!config.setEnabled(true)) { toast("设置保存失败"); setChecked(false); return; }
        ForwardService.start(this, true);
        setChecked(true);
    }

    private boolean saveSettings() {
        try {
            String endpoint = Policy.endpoint(address.getText().toString());
            String group = Policy.groupName(groupName.getText().toString());
            if (!config.savePushSettings(endpoint, group, archive.isChecked())) { toast("设置保存失败"); return false; }
            groupName.setText(group);
            address.setError(null);
            if (config.enabled()) ForwardService.start(this, true);
            return true;
        } catch (IllegalArgumentException e) {
            address.setError(e.getMessage());
            return false;
        }
    }

    private void testPush() {
        final String endpoint;
        try { endpoint = Policy.endpoint(address.getText().toString()); }
        catch (IllegalArgumentException e) { address.setError(e.getMessage()); return; }
        final String group = Policy.groupName(groupName.getText().toString());
        final boolean saveHistory = archive.isChecked();
        test.setEnabled(false);
        test.setText("发送中");
        executor.execute(() -> {
            String result;
            try {
                BarkClient.send(endpoint, "短信转发测试", "Bark 连接正常。", group, saveHistory);
                result = "测试已送达 Bark 服务端";
            } catch (IOException | IllegalArgumentException e) { result = "测试失败：" + e.getMessage(); }
            db.event(result);
            final String message = result;
            handler.post(() -> {
                if (isDestroyed()) return;
                test.setText("测试推送");
                test.setEnabled(true);
                toast(message);
                refresh();
            });
        });
    }

    private void openBattery() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已允许忽略系统电池优化");
            openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } else {
            openSettings(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (android.content.ActivityNotFoundException unavailable) { toast("请从手机设置打开应用详情"); }
        }
    }

    private void refresh() {
        boolean permission = local.barksms.Settings.hasPermission(this);
        boolean on = config.enabled() && permission;
        setChecked(on);
        status.setText(on ? "已启用" : config.enabled() ? "短信权限未开启" : "已暂停");
        status.setTextColor(on ? GREEN : 0xff8b4b21);
        pending.setText("待发送  " + db.count() + " 条");
        records.setText(db.recent());
    }

    private void setChecked(boolean checked) {
        updating = true;
        enabled.setChecked(checked);
        updating = false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) enable();
            else { config.setEnabled(false); toast("未获得短信接收权限"); }
            refresh();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (config.enabled() && local.barksms.Settings.hasPermission(this)) ForwardService.start(this, false);
        handler.post(refreshLoop);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refreshLoop);
        super.onPause();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(0xff202629);
        return view;
    }

    private Button button(String title, int icon) {
        Button view = new Button(this);
        view.setText(title); view.setTextSize(14); view.setAllCaps(false);
        view.setLetterSpacing(0);
        view.setMinWidth(0); view.setMinimumWidth(0);
        view.setPadding(dp(8), 0, dp(8), 0);
        android.graphics.drawable.Drawable drawable = getDrawable(icon);
        drawable.setTint(GREEN);
        drawable.setBounds(0, 0, dp(20), dp(20));
        view.setCompoundDrawables(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(4));
        return view;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(0xffe6e9e7);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(18); params.bottomMargin = dp(18);
        parent.addView(line, params);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
