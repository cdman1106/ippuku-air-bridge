package jp.ippuku.airbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BridgeService extends Service {

    public static final String ACTION_STATUS = "jp.ippuku.airbridge.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String ACTION_SET_TARGET = "jp.ippuku.airbridge.SET_TARGET";
    public static final String ACTION_SEND_BARCODE = "jp.ippuku.airbridge.SEND_BARCODE";
    public static final String ACTION_SEND_KEY = "jp.ippuku.airbridge.SEND_KEY";
    public static final String ACTION_MOUSE_MOVE = "jp.ippuku.airbridge.MOUSE_MOVE";
    public static final String ACTION_MOUSE_CLICK = "jp.ippuku.airbridge.MOUSE_CLICK";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_DX = "dx";
    public static final String EXTRA_DY = "dy";

    private static final String PREFS = "bridge";
    private static final String KEY_TARGET = "target_address";
    private static final String CHANNEL = "air_bridge";
    private static final int NOTIFICATION_ID = 2201;

    // Known-good keyboard-only HID descriptor.
    // This is the same input shape that successfully typed/searches barcodes in Airレジ.
    private static final byte[] REPORT_DESCRIPTOR = new byte[] {
            0x05,0x01,0x09,0x06,(byte)0xA1,0x01,
            0x05,0x07,0x19,(byte)0xE0,0x29,(byte)0xE7,
            0x15,0x00,0x25,0x01,0x75,0x01,(byte)0x95,0x08,(byte)0x81,0x02,
            (byte)0x95,0x01,0x75,0x08,(byte)0x81,0x01,
            (byte)0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,
            (byte)0x91,0x02,(byte)0x95,0x01,0x75,0x03,(byte)0x91,0x01,
            (byte)0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,
            0x19,0x00,0x29,0x65,(byte)0x81,0x00,(byte)0xC0
    };

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice target;
    private boolean appRegistered;
    private boolean connected;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            connectSavedTarget();
        }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            registerHidApp();
        }

        @Override public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = null;
            appRegistered = false;
            connected = false;
            publish("Bluetooth HIDサービスが切断。復旧待ち…");
            handler.postDelayed(() -> acquireHidProfile(), 3000);
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            appRegistered = registered;
            if (registered) {
                publish("Bluetoothバーコードリーダー待機中。注文専用iPadへ自動接続します。");
                connectSavedTarget();
            } else {
                connected = false;
                publish("HID登録解除。再登録します…");
                handler.postDelayed(() -> registerHidApp(), 2000);
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (target != null && !target.getAddress().equals(device.getAddress())) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                target = device;
                connected = true;
                String name = safeName(device);
                publish(name + " 接続済み。自動注文待機中。");
                handler.removeCallbacks(reconnectRunnable);
                flushPending();
                maybeSendOneTimeTest();
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                publish("注文専用iPadへ接続中…");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                publish("iPad切断。5秒後に自動再接続します…");
                scheduleReconnect();
            }
        }

        @Override public void onVirtualCableUnplug(BluetoothDevice device) {
            connected = false;
            publish("iPad側から切断されました。自動再接続します…");
            scheduleReconnect();
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                publish("Bluetooth復帰。Air Bridgeを再接続します…");
                handler.postDelayed(() -> acquireHidProfile(), 1200);
            } else if (state == BluetoothAdapter.STATE_OFF) {
                connected = false;
                appRegistered = false;
                publish("BluetoothがOFFです。");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("起動中…"));

        adapter = BluetoothAdapter.getDefaultAdapter();
        registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        acquireHidProfile();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // The service may have been created before Android's runtime Bluetooth
        // permission dialog completed. Every explicit start retries profile setup.
        handler.postDelayed(() -> acquireHidProfile(), 250);

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_SET_TARGET.equals(action)) {
                String address = intent.getStringExtra(EXTRA_ADDRESS);
                if (address != null && !address.isEmpty()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TARGET, address).apply();
                    connected = false;
                    connectSavedTarget();
                }
            } else if (ACTION_SEND_BARCODE.equals(action)) {
                String code = intent.getStringExtra(EXTRA_CODE);
                if (code != null && !code.trim().isEmpty()) enqueueBarcode(code.trim());
            } else if (ACTION_SEND_KEY.equals(action)) {
                String key = intent.getStringExtra(EXTRA_KEY);
                if (key != null) sendDiagnosticKey(key.trim().toUpperCase());
            } else if (ACTION_MOUSE_MOVE.equals(action) || ACTION_MOUSE_CLICK.equals(action)) {
                publish("マウス機能は停止中。Airレジ検索の安定動作を優先しています。");
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        try {
            if (hid != null && appRegistered) hid.unregisterApp();
        } catch (Exception ignored) {}
        try {
            if (adapter != null && hid != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        } catch (Exception ignored) {}
        sender.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireHidProfile() {
        if (adapter == null) {
            publish("このGalaxyではBluetoothを利用できません。");
            return;
        }
        if (!adapter.isEnabled()) {
            publish("GalaxyのBluetoothをONにしてください。");
            return;
        }
        if (hid != null) {
            if (!appRegistered) registerHidApp();
            else connectSavedTarget();
            return;
        }
        boolean requested;
        try {
            requested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) {
            publish("Bluetooth権限がありません。Air Bridgeを一度開いて許可してください。");
            return;
        }
        if (!requested) publish("Bluetooth HIDプロファイルを開始できませんでした。");
    }

    private void registerHidApp() {
        if (hid == null || appRegistered) return;

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "いっぷく Air Bridge",
                "Barcode Scanner",
                "ippuku",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                REPORT_DESCRIPTOR
        );

        try {
            boolean ok = hid.registerApp(
                    sdp,
                    (BluetoothHidDeviceAppQosSettings) null,
                    (BluetoothHidDeviceAppQosSettings) null,
                    getMainExecutor(),
                    hidCallback
            );
            if (!ok) publish("HID登録開始に失敗。BluetoothをOFF→ONしてください。");
            else publish("Bluetoothバーコードリーダーを準備中…");
        } catch (SecurityException e) {
            publish("Bluetooth権限エラー: " + String.valueOf(e.getMessage()));
        } catch (Exception e) {
            publish("HID登録エラー: " + e.getClass().getSimpleName());
        }
    }

    private void connectSavedTarget() {
        handler.removeCallbacks(reconnectRunnable);
        if (hid == null || !appRegistered || adapter == null || !adapter.isEnabled()) return;

        String address = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TARGET, "");
        if (address.isEmpty()) {
            BluetoothDevice auto = findSingleIpad();
            if (auto != null) {
                address = auto.getAddress();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TARGET, address).apply();
            } else {
                publish("注文専用iPadをまだ設定していません。");
                return;
            }
        }

        try {
            target = adapter.getRemoteDevice(address);
            int bond = target.getBondState();
            if (bond != BluetoothDevice.BOND_BONDED) {
                publish("iPadとのペアリング待ち。iPadのBluetooth設定からGalaxyを選んでください。");
                return;
            }
            if (connected) return;
            publish("注文専用iPadへ自動接続中…");
            boolean ok = hid.connect(target);
            if (!ok) scheduleReconnect();
        } catch (Exception e) {
            publish("iPad接続エラー。5秒後に再試行します…");
            scheduleReconnect();
        }
    }

    private BluetoothDevice findSingleIpad() {
        try {
            Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set == null) return null;
            BluetoothDevice found = null;
            int count = 0;
            for (BluetoothDevice d : set) {
                String n = d.getName();
                if (n != null && n.toLowerCase().contains("ipad")) {
                    found = d;
                    count++;
                }
            }
            return count == 1 ? found : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    private void scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, 5000);
    }

    private void enqueueBarcode(String code) {
        if (!code.matches("[0-9]+")) {
            publish("バーコードは数字のみ対応: " + code);
            return;
        }
        pending.offer(code);
        if (connected) flushPending();
        else {
            publish("注文を待機キューへ保存。iPad再接続後に自動送信します。");
            connectSavedTarget();
        }
    }

    private void flushPending() {
        if (!connected || target == null || hid == null) return;
        sender.execute(() -> {
            String code;
            while (connected && (code = pending.poll()) != null) {
                try {
                    typeBarcode(code);
                    Thread.sleep(250);
                    publish("バーコード送信完了: " + code);
                } catch (Exception e) {
                    pending.add(code);
                    connected = false;
                    publish("送信失敗。注文をキューに戻して再接続します…");
                    scheduleReconnect();
                    break;
                }
            }
        });
    }

    private void maybeSendOneTimeTest() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getBoolean("one_time_test_pending", false)) return;
        // Do not type into Airレジ automatically. This flag is only cleared here;
        // actual order traffic will come from the Cloudflare queue in the next step.
        p.edit().putBoolean("one_time_test_pending", false).apply();
    }

    private void sendDiagnosticKey(String key) {
        if (!connected || target == null || hid == null) {
            publish("キー送信不可：iPad未接続");
            return;
        }
        sender.execute(() -> {
            try {
                byte code;
                switch (key) {
                    case "ENTER": code = 0x28; break;
                    case "TAB": code = 0x2B; break;
                    case "DOWN": code = 0x51; break;
                    case "UP": code = 0x52; break;
                    case "ESC": code = 0x29; break;
                    case "SPACE": code = 0x2C; break;
                    default:
                        publish("未対応キー: " + key);
                        return;
                }
                press(code);
                publish("診断キー送信: " + key);
            } catch (Exception e) {
                publish("診断キー送信失敗: " + key);
            }
        });
    }

    private void moveMouse(int dx, int dy) {
        if (!connected || target == null || hid == null) {
            publish("マウス送信不可：iPad未接続");
            return;
        }
        sender.execute(() -> {
            try {
                int rx = dx, ry = dy;
                while (rx != 0 || ry != 0) {
                    int sx = Math.max(-127, Math.min(127, rx));
                    int sy = Math.max(-127, Math.min(127, ry));
                    byte[] report = new byte[]{0, (byte)sx, (byte)sy, 0};
                    if (!hid.sendReport(target, 2, report)) throw new IllegalStateException();
                    rx -= sx; ry -= sy;
                    Thread.sleep(18);
                }
                publish("マウス移動: " + dx + "," + dy);
            } catch (Exception e) {
                publish("マウス移動失敗");
            }
        });
    }

    private void clickMouse() {
        if (!connected || target == null || hid == null) {
            publish("クリック不可：iPad未接続");
            return;
        }
        sender.execute(() -> {
            try {
                if (!hid.sendReport(target, 2, new byte[]{1,0,0,0})) throw new IllegalStateException();
                Thread.sleep(45);
                if (!hid.sendReport(target, 2, new byte[]{0,0,0,0})) throw new IllegalStateException();
                publish("マウスクリック送信");
            } catch (Exception e) {
                publish("マウスクリック失敗");
            }
        });
    }

    private void sendDiagnosticMouse(String mode, int dx, int dy) {
        if (!connected || target == null || hid == null) {
            publish("マウス送信不可：iPad未接続");
            return;
        }
        sender.execute(() -> {
            try {
                switch (mode) {
                    case "HOME":
                        for (int i = 0; i < 18; i++) {
                            mouseReport((byte)0, -127, -127, 0);
                            Thread.sleep(12);
                        }
                        publish("マウスを左上へ移動");
                        break;
                    case "CLICK":
                        mouseClick();
                        publish("マウスクリック送信");
                        break;
                    case "MOVE":
                        mouseMove(dx, dy);
                        publish("マウス移動 x=" + dx + " y=" + dy);
                        break;
                    case "MOVECLICK":
                        mouseMove(dx, dy);
                        Thread.sleep(80);
                        mouseClick();
                        publish("マウス移動+クリック x=" + dx + " y=" + dy);
                        break;
                    default:
                        publish("未対応マウス操作: " + mode);
                }
            } catch (Exception e) {
                publish("マウス送信失敗: " + mode);
            }
        });
    }

    private void mouseMove(int dx, int dy) throws Exception {
        int x = dx;
        int y = dy;
        while (x != 0 || y != 0) {
            int sx = Math.max(-127, Math.min(127, x));
            int sy = Math.max(-127, Math.min(127, y));
            mouseReport((byte)0, sx, sy, 0);
            x -= sx;
            y -= sy;
            Thread.sleep(12);
        }
    }

    private void mouseClick() throws Exception {
        mouseReport((byte)1, 0, 0, 0);
        Thread.sleep(40);
        mouseReport((byte)0, 0, 0, 0);
        Thread.sleep(40);
    }

    private void mouseReport(byte buttons, int dx, int dy, int wheel) throws Exception {
        byte[] report = new byte[] {
                buttons,
                (byte)Math.max(-127, Math.min(127, dx)),
                (byte)Math.max(-127, Math.min(127, dy)),
                (byte)Math.max(-127, Math.min(127, wheel))
        };
        if (!hid.sendReport(target, 2, report)) {
            throw new IllegalStateException("mouse report failed");
        }
    }

    private void typeBarcode(String code) throws Exception {
        for (int i=0;i<code.length();i++) {
            Key k = keyForDigit(code.charAt(i));
            press(k.code);
            Thread.sleep(18);
        }
        press((byte)0x28); // Enter
    }

    private void press(byte keyCode) throws Exception {
        byte[] down = new byte[8];
        down[2] = keyCode;
        byte[] up = new byte[8];

        if (!hid.sendReport(target, 0, down)) throw new IllegalStateException("key down failed");
        Thread.sleep(28);
        if (!hid.sendReport(target, 0, up)) throw new IllegalStateException("key up failed");
        Thread.sleep(28);
    }

    private Key keyForDigit(char c) {
        if (c >= '1' && c <= '9') return new Key((byte)(0x1E + (c - '1')));
        if (c == '0') return new Key((byte)0x27);
        throw new IllegalArgumentException("数字以外");
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "iPad" : n;
        } catch (SecurityException e) {
            return "iPad";
        }
    }

    private void publish(String text) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_status", text).apply();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));

        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS, text);
        sendBroadcast(i);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel c = new NotificationChannel(
                CHANNEL,
                "いっぷく Air Bridge",
                NotificationManager.IMPORTANCE_LOW
        );
        c.setDescription("2F注文専用iPadとのBluetooth接続を維持します");
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(c);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b
                .setContentTitle("いっぷく Air Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    private static final class Key {
        final byte code;
        Key(byte code) { this.code = code; }
    }
}
