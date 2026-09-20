package jp.ippuku.airbridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_BT = 1001;
    private static final int DEFAULT_INTERVAL = 700;

    private static final UUID HID_SERVICE = uuid16(0x1812);
    private static final UUID HID_INFO = uuid16(0x2A4A);
    private static final UUID REPORT_MAP = uuid16(0x2A4B);
    private static final UUID HID_CONTROL_POINT = uuid16(0x2A4C);
    private static final UUID REPORT = uuid16(0x2A4D);
    private static final UUID PROTOCOL_MODE = uuid16(0x2A4E);
    private static final UUID BOOT_KEYBOARD_INPUT = uuid16(0x2A22);
    private static final UUID CCCD = uuid16(0x2902);
    private static final UUID REPORT_REFERENCE = uuid16(0x2908);

    private static final byte[] KEYBOARD_REPORT_MAP = new byte[] {
            0x05,0x01,0x09,0x06,(byte)0xA1,0x01,(byte)0x85,0x01,
            0x05,0x07,0x19,(byte)0xE0,0x29,(byte)0xE7,0x15,0x00,0x25,0x01,
            0x75,0x01,(byte)0x95,0x08,(byte)0x81,0x02,
            (byte)0x95,0x01,0x75,0x08,(byte)0x81,0x01,
            (byte)0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,
            (byte)0x91,0x02,(byte)0x95,0x01,0x75,0x03,(byte)0x91,0x01,
            (byte)0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,
            0x19,0x00,0x29,0x65,(byte)0x81,0x00,(byte)0xC0
    };

    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic inputReport;
    private BluetoothGattCharacteristic bootInputReport;
    private BluetoothDevice host;
    private boolean notificationsEnabled;
    private boolean advertising;

    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private TextView status;
    private EditText oneCode;
    private EditText batchCodes;
    private EditText interval;
    private Button startBle;
    private Button testTyping;
    private Button sendOne;
    private Button sendBatch;

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            setStatus("BLEキーボード待機中。iPadの 設定 > Bluetooth でこのAndroid端末を選んでください。");
            updateButtons();
        }

        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            String reason;
            switch (errorCode) {
                case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                    reason = "広告データが大きすぎます";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    reason = "同時BLE広告数の上限です";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                    reason = "BLE広告は既に開始済みです";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                    reason = "Bluetooth内部エラーです";
                    break;
                case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    reason = "この端末はBLE周辺機器広告に非対応です";
                    break;
                default:
                    reason = "不明なBLE広告エラーです";
            }
            setStatus("BLE広告開始失敗: " + errorCode + "（" + reason + "）");
            updateButtons();
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                notificationsEnabled = false;
                setStatus("iPad接続済み。キーボード通知の準備待ち…");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (host != null && host.getAddress().equals(device.getAddress())) host = null;
                notificationsEnabled = false;
                setStatus("iPad切断。Bluetooth設定から再接続してください。");
            }
            updateButtons();
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                           int offset, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null) value = new byte[0];
            if (offset > value.length) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                return;
            }
            byte[] slice = new byte[value.length - offset];
            System.arraycopy(value, offset, slice, 0, slice.length);
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                            BluetoothGattCharacteristic characteristic,
                                                            boolean preparedWrite, boolean responseNeeded,
                                                            int offset, byte[] value) {
            if (value != null) characteristic.setValue(value);
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                                       int offset, BluetoothGattDescriptor descriptor) {
            byte[] value = descriptor.getValue();
            if (value == null) value = new byte[0];
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                        BluetoothGattDescriptor descriptor,
                                                        boolean preparedWrite, boolean responseNeeded,
                                                        int offset, byte[] value) {
            descriptor.setValue(value);
            if (CCCD.equals(descriptor.getUuid())) {
                notificationsEnabled = value != null && value.length >= 2 &&
                        value[0] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0] &&
                        value[1] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[1];
                if (notificationsEnabled) {
                    setStatus("iPad接続完了。TEST123を送れます。");
                }
                updateButtons();
            }
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }
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
        stopBle();
    }

    private void buildUi() {
        int p = dp(16);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("いっぷく Air Bridge BLE");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        body.addView(title);

        status = new TextView(this);
        status.setText("準備中…");
        status.setPadding(0,p,0,p);
        body.addView(status);

        startBle = new Button(this);
        startBle.setText("BLEキーボードを開始");
        startBle.setOnClickListener(v -> startBleKeyboard());
        body.addView(startBle);

        Button usbTest = new Button(this);
        usbTest.setText("USB有線モードを試す");
        usbTest.setOnClickListener(v ->
                startActivity(new Intent(this, UsbHidDiagnosticsActivity.class)));
        body.addView(usbTest);

        testTyping = new Button(this);
        testTyping.setText("接続テスト：iPadへ TEST123 を送信");
        testTyping.setOnClickListener(v -> sendTextOnly("TEST123"));
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

        TextView note = new TextView(this);
        note.setText("\n使い方: ①BLEキーボード開始 → ②iPadの設定>BluetoothでAndroid端末を選択 → ③iPadのメモを開いてTEST123送信");
        note.setPadding(0,p,0,0);
        body.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
        updateButtons();
    }

    private void startBleKeyboard() {
        if (!hasBtPermissions()) {
            requestBtPermissions();
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("AndroidのBluetoothをONにしてください。");
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            setStatus("このAndroid端末はBLE周辺機器モードに対応していません。");
            return;
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        try {
            gattServer = manager.openGattServer(this, gattCallback);
        } catch (SecurityException e) {
            setStatus("Bluetooth権限が必要です。");
            return;
        }
        if (gattServer == null) {
            setStatus("BLE GATTサーバーを開始できませんでした。");
            return;
        }

        BluetoothGattService hid = new BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic info = new BluetoothGattCharacteristic(
                HID_INFO, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        info.setValue(new byte[]{0x11,0x01,0x00,0x02});
        hid.addCharacteristic(info);

        BluetoothGattCharacteristic map = new BluetoothGattCharacteristic(
                REPORT_MAP, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        map.setValue(KEYBOARD_REPORT_MAP);
        hid.addCharacteristic(map);

        BluetoothGattCharacteristic protocol = new BluetoothGattCharacteristic(
                PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        protocol.setValue(new byte[]{0x01});
        hid.addCharacteristic(protocol);

        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(
                HID_CONTROL_POINT, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        hid.addCharacteristic(control);

        inputReport = new BluetoothGattCharacteristic(
                REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);

        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        inputReport.addDescriptor(cccd);

        BluetoothGattDescriptor ref = new BluetoothGattDescriptor(
                REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ);
        ref.setValue(new byte[]{0x01,0x01});
        inputReport.addDescriptor(ref);
        inputReport.setValue(new byte[8]);
        hid.addCharacteristic(inputReport);

        bootInputReport = new BluetoothGattCharacteristic(
                BOOT_KEYBOARD_INPUT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        BluetoothGattDescriptor bootCccd = new BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED);
        bootCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        bootInputReport.addDescriptor(bootCccd);
        bootInputReport.setValue(new byte[8]);
        hid.addCharacteristic(bootInputReport);

        if (!gattServer.addService(hid)) {
            setStatus("HIDサービスを追加できませんでした。");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();

        // Minimal advertisement: HID service UUID only.
        // Do not include the Android device name at all because long local
        // names can also overflow the 31-byte scan-response limit and cause
        // ADVERTISE_FAILED_DATA_TOO_LARGE (error 1).
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HID_SERVICE))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
            setStatus("BLE広告を開始中…");
        } catch (SecurityException e) {
            setStatus("BLE広告権限がありません。");
        }
    }

    private void stopBle() {
        try {
            if (advertiser != null && advertising) advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignored) {}
        advertising = false;
        if (gattServer != null) {
            try { gattServer.close(); } catch (Exception ignored) {}
        }
        gattServer = null;
        host = null;
        notificationsEnabled = false;
    }

    private void sendTextOnly(String text) {
        if (!readyToSend()) {
            toast("iPadがまだBLEキーボードとして接続されていません。");
            return;
        }
        sender.execute(() -> {
            try {
                typeText(text);
                setStatus("TEST123を送信しました。iPadのメモを確認してください。");
            } catch (Exception e) {
                setStatus("送信エラー: " + e.getMessage());
            }
        });
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
        if (!readyToSend()) {
            toast("iPadがまだBLEキーボードとして接続されていません。");
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
                    press((byte)0x28, (byte)0x00);
                    n++;
                    final int done = n;
                    setStatus("送信中… " + done + "/" + codes.size());
                    if (wait > 0 && n < codes.size()) Thread.sleep(wait);
                }
                setStatus("送信完了: " + codes.size() + "件。Airレジの伝票を確認してください。");
            } catch (Exception e) {
                setStatus("送信エラー: " + e.getMessage());
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

        notifyReport(down);
        Thread.sleep(35);
        notifyReport(up);
        Thread.sleep(35);
    }

    private void notifyReport(byte[] value) throws Exception {
        if (!readyToSend()) throw new IllegalStateException("iPad未接続");

        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            int result = gattServer.notifyCharacteristicChanged(host, inputReport, false, value);
            ok = result == BluetoothStatusCodes.SUCCESS;
        } else {
            inputReport.setValue(value);
            ok = gattServer.notifyCharacteristicChanged(host, inputReport, false);
        }
        if (!ok) throw new IllegalStateException("BLE通知送信失敗");
    }

    private boolean readyToSend() {
        return gattServer != null && host != null && notificationsEnabled;
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
        setStatus("準備完了。「BLEキーボードを開始」を押してください。");
        updateButtons();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_BT) {
            setStatus(hasBtPermissions()
                    ? "準備完了。「BLEキーボードを開始」を押してください。"
                    : "Bluetooth権限を許可してください。");
            updateButtons();
        }
    }

    private boolean hasBtPermissions() {
        return Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }

    private void updateButtons() {
        if (startBle == null) return;
        boolean ready = readyToSend();
        testTyping.setEnabled(ready);
        sendOne.setEnabled(ready);
        sendBatch.setEnabled(ready);
    }

    private void setStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static UUID uuid16(int shortUuid) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid));
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
