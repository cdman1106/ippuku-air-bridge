package jp.ippuku.airbridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_BT = 1001;
    private static final int DEFAULT_INTERVAL = 700;

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered;

    private final List<BluetoothDevice> paired = new ArrayList<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private TextView status;
    private Spinner hosts;
    private EditText oneCode;
    private EditText batchCodes;
    private EditText interval;
    private Button connect;
    private Button sendOne;
    private Button sendBatch;

    private static final byte[] KEYBOARD_DESCRIPTOR = new byte[] {
            0x05,0x01,0x09,0x06,(byte)0xA1,0x01,0x05,0x07,
            0x19,(byte)0xE0,0x29,(byte)0xE7,0x15,0x00,0x25,0x01,
            0x75,0x01,(byte)0x95,0x08,(byte)0x81,0x02,
            (byte)0x95,0x01,0x75,0x08,(byte)0x81,0x01,
            (byte)0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,
            (byte)0x91,0x02,(byte)0x95,0x01,0x75,0x03,(byte)0x91,0x01,
            (byte)0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,
            0x19,0x00,0x29,0x65,(byte)0x81,0x00,(byte)0xC0
    };

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    hid = (BluetoothHidDevice) proxy;
                    setStatus("Bluetooth HID準備完了。HID登録中…");
                    registerHid();
                }

                @Override public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hid = null;
                        registered = false;
                        setStatus("Bluetooth HIDサービスが切断されました。");
                        updateButtons();
                    }
                }
            };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            runOnUiThread(() -> {
                setStatus(isRegistered
                        ? "HIDキーボード登録済み。iPadのBluetooth設定からこのAndroid端末をペアリングしてください。"
                        : "HID登録失敗。このAndroid端末がBluetooth HID Deviceに非対応の可能性があります。");
                refreshPaired();
                updateButtons();
            });
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            runOnUiThread(() -> {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    host = device;
                    setStatus("接続済み: " + nameOf(device) + "。Airレジの検索欄を選択して送信できます。");
                } else if (state == BluetoothProfile.STATE_CONNECTING) {
                    setStatus("接続中: " + nameOf(device));
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    setStatus("切断: " + nameOf(device));
                }
                updateButtons();
            });
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        if (adapter == null) {
            setStatus("このAndroid端末ではBluetoothを利用できません。");
            return;
        }

        requestBtPermissions();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        sender.shutdownNow();
        if (adapter != null && hid != null && hasConnect()) {
            try {
                if (registered) hid.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            } catch (Exception ignored) {}
        }
    }

    private void buildUi() {
        int p = dp(16);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("いっぷく Air Bridge");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        body.addView(title);

        status = new TextView(this);
        status.setText("準備中…");
        status.setPadding(0,p,0,p);
        body.addView(status);

        Button discover = new Button(this);
        discover.setText("iPadから検索できるようにする");
        discover.setOnClickListener(v -> requestDiscoverable());
        body.addView(discover);

        Button refresh = new Button(this);
        refresh.setText("ペア済み端末を更新");
        refresh.setOnClickListener(v -> refreshPaired());
        body.addView(refresh);

        hosts = new Spinner(this);
        hosts.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"ペア済み端末なし"}));
        hosts.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 android.view.View view, int position, long id) {
                host = position < paired.size() ? paired.get(position) : null;
                updateButtons();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                host = null;
                updateButtons();
            }
        });
        body.addView(hosts);

        connect = new Button(this);
        connect.setText("選択したiPadへ接続");
        connect.setOnClickListener(v -> connectHost());
        body.addView(connect);

        Button testTyping = new Button(this);
        testTyping.setText("接続テスト：iPadへ TEST123 を送信");
        testTyping.setOnClickListener(v -> {
            if (!isHostConnected()) {
                toast("まだiPadとHID接続できていません。画面上部の状態を確認してください。");
                return;
            }
            sender.execute(() -> {
                try {
                    typeText("TEST123");
                    runOnUiThread(() -> setStatus("TEST123 を送信しました。iPadのメモ等に表示されたか確認してください。"));
                } catch (Exception e) {
                    runOnUiThread(() -> setStatus("テスト送信エラー: " + e.getMessage()));
                }
            });
        });
        body.addView(testTyping);

        TextView t1 = new TextView(this);
        t1.setText("\n1商品テスト");
        body.addView(t1);

        oneCode = new EditText(this);
        oneCode.setHint("例: 100001");
        oneCode.setSingleLine(true);
        oneCode.setInputType(InputType.TYPE_CLASS_TEXT);
        body.addView(oneCode);

        sendOne = new Button(this);
        sendOne.setText("バーコード番号 + Enter を送信");
        sendOne.setOnClickListener(v -> {
            String code = oneCode.getText().toString().trim();
            if (code.isEmpty()) {
                toast("バーコード番号を入力してください。");
                return;
            }
            List<String> codes = new ArrayList<>();
            codes.add(code);
            sendCodes(codes, 0);
        });
        body.addView(sendOne);

        TextView t2 = new TextView(this);
        t2.setText("\n複数商品（1行1コード）");
        body.addView(t2);

        batchCodes = new EditText(this);
        batchCodes.setHint("100001\n200001\n200001\n900001");
        batchCodes.setMinLines(6);
        batchCodes.setGravity(Gravity.TOP);
        body.addView(batchCodes);

        interval = new EditText(this);
        interval.setHint("送信間隔(ms)");
        interval.setText(String.valueOf(DEFAULT_INTERVAL));
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(interval);

        sendBatch = new Button(this);
        sendBatch.setText("全部Airレジへ送信");
        sendBatch.setOnClickListener(v -> sendBatch());
        body.addView(sendBatch);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);

        updateButtons();
    }

    private void startHidProfile() {
        if (!hasConnect()) return;
        try {
            boolean ok = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE);
            if (!ok) setStatus("Bluetooth HIDプロファイルを開始できませんでした。");
        } catch (SecurityException e) {
            setStatus("Bluetooth権限が必要です。");
        }
    }

    private void registerHid() {
        if (hid == null || !hasConnect()) return;

        BluetoothHidDeviceAppSdpSettings sdp =
                new BluetoothHidDeviceAppSdpSettings(
                        "Ippuku Air Bridge",
                        "AirRegi barcode keyboard bridge",
                        "Ippuku",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        KEYBOARD_DESCRIPTOR
                );

        try {
            boolean ok = hid.registerApp(sdp, null, null, Runnable::run, hidCallback);
            if (!ok) setStatus("HIDアプリ登録要求を開始できませんでした。");
        } catch (Exception e) {
            setStatus("HID登録エラー: " + e.getMessage());
        }
    }

    private void requestDiscoverable() {
        if (!hasAdvertise()) {
            requestBtPermissions();
            return;
        }
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(i);
        toast("iPadの 設定 > Bluetooth から、このAndroid端末を選んでください。");
    }

    private void refreshPaired() {
        paired.clear();
        if (!hasConnect()) return;

        try {
            Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) paired.addAll(set);
            paired.sort(Comparator.comparing(this::nameOf));

            List<String> labels = new ArrayList<>();
            for (BluetoothDevice d : paired) labels.add(nameOf(d));

            if (labels.isEmpty()) labels.add("ペア済み端末なし");
            hosts.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels));

            host = paired.isEmpty() ? null : paired.get(0);
            updateButtons();
        } catch (SecurityException e) {
            setStatus("ペア済み端末取得にBluetooth権限が必要です。");
        }
    }

    private void connectHost() {
        if (hid == null || !registered || host == null || !hasConnect()) {
            toast("先にHID登録とiPadのペアリングをしてください。");
            return;
        }
        try {
            setStatus("接続要求中: " + nameOf(host));
            if (!hid.connect(host)) setStatus("接続要求を開始できませんでした。");
        } catch (Exception e) {
            setStatus("接続エラー: " + e.getMessage());
        }
    }

    private void sendBatch() {
        String[] lines = batchCodes.getText().toString().split("\\r?\\n");
        List<String> codes = new ArrayList<>();
        for (String line : lines) {
            String s = line.trim();
            if (!s.isEmpty()) codes.add(s);
        }

        if (codes.isEmpty()) {
            toast("1行に1つバーコード番号を入力してください。");
            return;
        }

        int wait = DEFAULT_INTERVAL;
        try {
            wait = Math.max(150, Integer.parseInt(interval.getText().toString().trim()));
        } catch (Exception ignored) {}

        sendCodes(codes, wait);
    }

    private void sendCodes(List<String> codes, int wait) {
        if (!isHostConnected()) {
            toast("iPadへBluetooth HID接続してください。上部に「接続済み」と表示されている必要があります。");
            return;
        }

        sender.execute(() -> {
            runOnUiThread(() -> {
                sendOne.setEnabled(false);
                sendBatch.setEnabled(false);
                setStatus("送信中… 0/" + codes.size());
            });

            try {
                int n = 0;
                for (String code : codes) {
                    typeText(code);
                    press((byte)0x28, (byte)0x00); // Enter
                    n++;
                    final int done = n;
                    runOnUiThread(() -> setStatus("送信中… " + done + "/" + codes.size()));
                    if (wait > 0 && n < codes.size()) Thread.sleep(wait);
                }
                runOnUiThread(() -> setStatus("送信完了: " + codes.size() + "件。Airレジの伝票を確認してください。"));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("送信エラー: " + e.getMessage()));
            } finally {
                runOnUiThread(this::updateButtons);
            }
        });
    }

    private void typeText(String s) throws Exception {
        for (int i=0; i<s.length(); i++) {
            Key k = keyFor(s.charAt(i));
            if (k == null) throw new IllegalArgumentException("未対応文字: " + s.charAt(i));
            press(k.code, k.modifier);
            Thread.sleep(25);
        }
    }

    private void press(byte code, byte modifier) throws Exception {
        byte[] down = new byte[8];
        down[0] = modifier;
        down[2] = code;
        byte[] up = new byte[8];

        boolean a = hid.sendReport(host, 0, down);
        Thread.sleep(30);
        boolean b = hid.sendReport(host, 0, up);
        Thread.sleep(30);

        if (!a || !b) throw new IllegalStateException("HIDレポート送信失敗");
    }

    private Key keyFor(char c) {
        if (c >= '1' && c <= '9') return new Key((byte)(0x1E + (c - '1')), (byte)0);
        if (c == '0') return new Key((byte)0x27, (byte)0);
        if (c >= 'a' && c <= 'z') return new Key((byte)(0x04 + (c - 'a')), (byte)0);
        if (c >= 'A' && c <= 'Z') return new Key((byte)(0x04 + (c - 'A')), (byte)0x02);

        switch (c) {
            case '-': return new Key((byte)0x2D, (byte)0);
            case '_': return new Key((byte)0x2D, (byte)0x02);
            case '=': return new Key((byte)0x2E, (byte)0);
            case '.': return new Key((byte)0x37, (byte)0);
            case '/': return new Key((byte)0x38, (byte)0);
            case ' ': return new Key((byte)0x2C, (byte)0);
            default: return null;
        }
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> need = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_ADVERTISE);

            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_BT);
                return;
            }
        }
        startHidProfile();
        refreshPaired();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_BT) {
            startHidProfile();
            refreshPaired();
        }
    }

    private boolean hasConnect() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAdvertise() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private String nameOf(BluetoothDevice d) {
        if (d == null) return "不明な端末";
        try {
            String n = hasConnect() ? d.getName() : null;
            return (n == null || n.trim().isEmpty()) ? d.getAddress() : n + " • " + d.getAddress();
        } catch (Exception e) {
            return "Bluetooth端末";
        }
    }

    private boolean isHostConnected() {
        if (hid == null || host == null || !hasConnect()) return false;
        try {
            return hid.getConnectionState(host) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateButtons() {
        if (connect == null) return;

        boolean canConnect = registered && hid != null && host != null && hasConnect();
        connect.setEnabled(canConnect);

        boolean connected = canConnect && isHostConnected();

        sendOne.setEnabled(connected);
        sendBatch.setEnabled(connected);
    }

    private void setStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Key {
        final byte code;
        final byte modifier;
        Key(byte code, byte modifier) {
            this.code = code;
            this.modifier = modifier;
        }
    }
}
