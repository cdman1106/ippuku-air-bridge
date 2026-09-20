package jp.ippuku.airbridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_BT = 1001;
    private static final int REQ_DISCOVERABLE = 1002;
    private static final String PREFS = "bridge";
    private static final String KEY_TARGET = "target_address";

    private BluetoothAdapter adapter;
    private TextView status;
    private Spinner devices;
    private final List<BluetoothDevice> bonded = new ArrayList<>();
    private BroadcastReceiver statusReceiver;
    private boolean receiverRegistered;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        adapter = BluetoothAdapter.getDefaultAdapter();
        registerStatusReceiver();
        if (hasBluetoothPermissions()) {
            startBridgeService();
            refreshBondedDevices();
        } else {
            status.setText("Bluetooth権限を許可してください。");
            requestPermissionsIfNeeded();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasBluetoothPermissions()) {
            startBridgeService();
            refreshBondedDevices();
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = p.getString("last_status", "Bluetooth待機中…");
        status.setText(last);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        }
    }

    private void buildUi() {
        int p = dp(16);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("いっぷく Air Bridge");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        body.addView(title);

        TextView sub = new TextView(this);
        sub.setText("2F注文専用iPadへBluetoothで自動入力");
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(16));
        body.addView(sub);

        status = new TextView(this);
        status.setText("準備中…");
        status.setTextSize(18);
        status.setPadding(0,0,0,p);
        body.addView(status);

        Button discoverable = new Button(this);
        discoverable.setText("① iPadからこのGalaxyを見つけられるようにする");
        discoverable.setOnClickListener(v -> makeDiscoverable());
        body.addView(discoverable);

        Button refresh = new Button(this);
        refresh.setText("② ペア済み端末を更新");
        refresh.setOnClickListener(v -> refreshBondedDevices());
        body.addView(refresh);

        devices = new Spinner(this);
        body.addView(devices);

        Button select = new Button(this);
        select.setText("③ このiPadを2F注文専用に設定");
        select.setOnClickListener(v -> saveSelectedTarget());
        body.addView(select);

        Button btSettings = new Button(this);
        btSettings.setText("iPadが出ない場合：Bluetooth設定を開く");
        btSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        body.addView(btSettings);

        TextView note = new TextView(this);
        note.setPadding(0,p,0,0);
        note.setText(
                "運用中に商品を手動送信するボタンはありません。\n" +
                "設定後はAir Bridgeがバックグラウンド常駐し、切断時も自動再接続します。\n\n" +
                "次の段階でCloudflareの注文キューを接続し、注文受信→商品入力→一時保存まで自動化します。");
        body.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        setContentView(sv);
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 31) return;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_BT);
    }

    private boolean hasBluetoothPermissions() {
        return Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }

    private void makeDiscoverable() {
        if (!hasBluetoothPermissions()) {
            requestPermissionsIfNeeded();
            return;
        }
        if (adapter == null) {
            toast("Bluetoothを利用できません。");
            return;
        }
        if (!adapter.isEnabled()) {
            toast("GalaxyのBluetoothをONにしてください。");
            return;
        }
        startBridgeService();
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(i, REQ_DISCOVERABLE);
        status.setText("iPadで 設定 > Bluetooth を開き、このGalaxyを選んでペアリングしてください。");
    }

    private void refreshBondedDevices() {
        if (adapter == null || !hasBluetoothPermissions()) return;
        bonded.clear();
        try {
            Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) bonded.addAll(set);
        } catch (SecurityException ignored) {}

        List<String> labels = new ArrayList<>();
        int selected = 0;
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TARGET, "");
        for (int i=0;i<bonded.size();i++) {
            BluetoothDevice d = bonded.get(i);
            String name;
            try { name = d.getName(); } catch (SecurityException e) { name = null; }
            if (name == null || name.trim().isEmpty()) name = "Bluetooth端末";
            labels.add(name + "  (" + d.getAddress() + ")");
            if (d.getAddress().equals(saved)) selected = i;
        }
        if (labels.isEmpty()) labels.add("ペア済み端末なし");
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        devices.setAdapter(a);
        if (!bonded.isEmpty()) devices.setSelection(selected);
    }

    private void saveSelectedTarget() {
        if (bonded.isEmpty()) {
            toast("先にiPadとBluetoothペアリングしてください。");
            return;
        }
        int pos = devices.getSelectedItemPosition();
        if (pos < 0 || pos >= bonded.size()) return;
        BluetoothDevice d = bonded.get(pos);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TARGET, d.getAddress())
                .putBoolean("one_time_test_pending", true)
                .apply();

        Intent svc = new Intent(this, BridgeService.class);
        svc.setAction(BridgeService.ACTION_SET_TARGET);
        svc.putExtra(BridgeService.EXTRA_ADDRESS, d.getAddress());
        startForegroundCompat(svc);

        String name;
        try { name = d.getName(); } catch (SecurityException e) { name = "iPad"; }
        status.setText("注文専用端末を " + (name == null ? "iPad" : name) + " に設定。自動接続中…");
        toast("設定しました。以後は自動再接続します。");
    }

    private void startBridgeService() {
        startForegroundCompat(new Intent(this, BridgeService.class));
    }

    private void startForegroundCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void registerStatusReceiver() {
        statusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!BridgeService.ACTION_STATUS.equals(intent.getAction())) return;
                String s = intent.getStringExtra(BridgeService.EXTRA_STATUS);
                if (s != null) status.setText(s);
            }
        };
        IntentFilter f = new IntentFilter(BridgeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, f);
        receiverRegistered = true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_BT) {
            if (hasBluetoothPermissions()) {
                status.setText("Bluetooth権限OK。接続サービスを開始します…");
                startBridgeService();
                refreshBondedDevices();
            } else {
                status.setText("Bluetooth権限が未許可です。Galaxyの 設定 > アプリ > いっぷく Air Bridge > 権限 で「付近のデバイス」を許可してください。");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
