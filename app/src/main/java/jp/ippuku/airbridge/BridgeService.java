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
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeService extends Service {

    private static final String TAG = "IppukuAirBridge";

    public static final String ACTION_STATUS = "jp.ippuku.airbridge.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_HISTORY = "history";
    public static final String ACTION_SET_TARGET = "jp.ippuku.airbridge.SET_TARGET";
    public static final String ACTION_SEND_BARCODE = "jp.ippuku.airbridge.SEND_BARCODE";
    public static final String ACTION_SEND_KEY = "jp.ippuku.airbridge.SEND_KEY";
    public static final String ACTION_SEND_KEY_SEQUENCE = "jp.ippuku.airbridge.SEND_KEY_SEQUENCE";
    public static final String ACTION_SEND_BARCODE_ONLY = "jp.ippuku.airbridge.SEND_BARCODE_ONLY";
    public static final String ACTION_SEND_MACRO = "jp.ippuku.airbridge.SEND_MACRO";
    public static final String ACTION_RUN_FULL_FLOW = "jp.ippuku.airbridge.RUN_FULL_FLOW";
    public static final String ACTION_SET_AUTO_BRIDGE = "jp.ippuku.airbridge.SET_AUTO_BRIDGE";
    public static final String ACTION_PREPARE_NEXT_ORDER = "jp.ippuku.airbridge.PREPARE_NEXT_ORDER";
    public static final String ACTION_TEST_BRIDGE_API = "jp.ippuku.airbridge.TEST_BRIDGE_API";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_SEQUENCE = "sequence";
    public static final String EXTRA_GAP_MS = "gap_ms";
    public static final String EXTRA_MACRO = "macro";
    public static final String EXTRA_ENABLED = "enabled";

    private static final String PREFS = "bridge";
    private static final String KEY_TARGET = "target_address";
    private static final String KEY_AUTO_BRIDGE = "auto_bridge_enabled";
    private static final String KEY_NEEDS_NEXT_PREP = "needs_next_order_prep";
    private static final String KEY_PRODUCT_SELECT_TABS = "product_select_tabs";
    private static final String KEY_LEARNED_FLOW = "learned_airregi_flow";
    private static final String KEY_BRIDGE_BASE_URL = "bridge_api_base_url";
    private static final String DEFAULT_BRIDGE_BASE_URL = "https://ippuku-kanri.cdman1106.workers.dev";
    private static final String CHANNEL = "air_bridge";
    private static final int NOTIFICATION_ID = 2201;

    // Proven keyboard-only HID descriptor. Airレジ検索との互換性を優先。
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
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AtomicBoolean bridgeBusy = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private String lastBridgeNotice = "";

    private final Runnable bridgePollRunnable = new Runnable() {
        @Override public void run() {
            try {
                if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_BRIDGE, false)) {
                    pollBridgeQueue();
                }
            } finally {
                handler.postDelayed(this, 3000);
            }
        }
    };

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
        handler.postDelayed(bridgePollRunnable, 2500);
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
            } else if (ACTION_SEND_BARCODE_ONLY.equals(action)) {
                String code = intent.getStringExtra(EXTRA_CODE);
                if (code != null && !code.trim().isEmpty()) sendBarcodeOnly(code.trim());
            } else if (ACTION_SEND_KEY.equals(action)) {
                String key = intent.getStringExtra(EXTRA_KEY);
                if (key != null) sendDiagnosticKey(key.trim().toUpperCase());
            } else if (ACTION_SEND_KEY_SEQUENCE.equals(action)) {
                String sequence = intent.getStringExtra(EXTRA_SEQUENCE);
                int gapMs = Math.max(100, intent.getIntExtra(EXTRA_GAP_MS, 350));
                if (sequence != null) sendDiagnosticSequence(sequence, gapMs);
            } else if (ACTION_SEND_MACRO.equals(action)) {
                String macro = intent.getStringExtra(EXTRA_MACRO);
                if (macro != null && !macro.trim().isEmpty()) sendMacro(macro.trim());
            } else if (ACTION_RUN_FULL_FLOW.equals(action)) {
                String code = intent.getStringExtra(EXTRA_CODE);
                if (code != null && !code.trim().isEmpty()) runFullFlow(code.trim());
            } else if (ACTION_SET_AUTO_BRIDGE.equals(action)) {
                boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO_BRIDGE, enabled).apply();
                if (enabled) {
                    publish("自動注文ON：Cloudflareの新規注文を監視します。");
                    handler.removeCallbacks(bridgePollRunnable);
                    handler.post(bridgePollRunnable);
                } else {
                    publish("自動注文OFF：監視を停止しました。");
                }
            } else if (ACTION_PREPARE_NEXT_ORDER.equals(action)) {
                prepareNextOrderManually();
            } else if (ACTION_TEST_BRIDGE_API.equals(action)) {
                testBridgeApi();
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
        network.shutdownNow();
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
                sendNamedKey(key);
                publish("診断キー送信: " + key);
            } catch (Exception e) {
                publish("診断キー送信失敗: " + key);
            }
        });
    }

    private void sendDiagnosticSequence(String sequence, int gapMs) {
        if (!connected || target == null || hid == null) {
            publish("キー列送信不可：iPad未接続");
            return;
        }
        sender.execute(() -> {
            try {
                String[] keys = sequence.toUpperCase().split(",");
                for (String raw : keys) {
                    String key = raw.trim();
                    if (key.isEmpty()) continue;
                    sendNamedKey(key);
                    Thread.sleep(gapMs);
                }
                publish("診断キー列送信完了: " + sequence);
            } catch (Exception e) {
                publish("診断キー列送信失敗: " + sequence);
            }
        });
    }

    private void sendNamedKey(String key) throws Exception {
        switch (key) {
            case "ENTER": press((byte)0x28); break;
            case "TAB": press((byte)0x2B); break;
            case "SHIFT_TAB": press((byte)0x02, (byte)0x2B); break;
            case "DOWN": press((byte)0x51); break;
            case "UP": press((byte)0x52); break;
            case "LEFT": press((byte)0x50); break;
            case "RIGHT": press((byte)0x4F); break;
            case "ESC": press((byte)0x29); break;
            case "SPACE": press((byte)0x2C); break;
            case "BACKSPACE": press((byte)0x2A); break;
            case "DELETE": press((byte)0x4C); break;
            case "HOME": press((byte)0x4A); break;
            case "END": press((byte)0x4D); break;
            case "CMD_A": press((byte)0x08, (byte)0x04); break;
            case "CTRL_A": press((byte)0x01, (byte)0x04); break;
            case "OPTION": press((byte)0x04, (byte)0x00); break;
            case "KP1": press((byte)0x59); break;
            case "KP2": press((byte)0x5A); break;
            case "KP3": press((byte)0x5B); break;
            case "KP4": press((byte)0x5C); break;
            case "KP5": press((byte)0x5D); break;
            case "KP6": press((byte)0x5E); break;
            case "KP7": press((byte)0x5F); break;
            case "KP8": press((byte)0x60); break;
            case "KP9": press((byte)0x61); break;
            case "KP0": press((byte)0x62); break;
            case "KP_DOT": press((byte)0x63); break;
            default: throw new IllegalArgumentException("unsupported key: " + key);
        }
    }

    private void sendMacro(String macro) {
        if (!connected || target == null || hid == null) {
            publish("マクロ送信不可：iPad未接続");
            return;
        }
        sender.execute(() -> {
            boolean sentAny = false;
            try {
                String[] tokens = macro.split(",");
                Log.i(TAG, "MACRO start: " + macro);
                for (String raw : tokens) {
                    String token = raw.trim();
                    if (token.isEmpty()) continue;

                    if (token.toUpperCase().startsWith("WAIT:")) {
                        int ms = Integer.parseInt(token.substring(5).trim());
                        ms = Math.max(0, Math.min(10000, ms));
                        Log.i(TAG, "MACRO wait " + ms + "ms");
                        Thread.sleep(ms);
                        continue;
                    }

                    if (token.toUpperCase().startsWith("TEXT:")) {
                        String text = token.substring(5);
                        Log.i(TAG, "MACRO text len=" + text.length());
                        typeAscii(text);
                        sentAny = true;
                        continue;
                    }

                    if ("MOUSEKEYS_TOGGLE".equalsIgnoreCase(token)) {
                        Log.i(TAG, "MACRO toggle Mouse Keys via Option x5");
                        for (int i = 0; i < 5; i++) {
                            press((byte)0x04, (byte)0x00); // Option/Alt modifier only
                            if (i < 4) Thread.sleep(120);
                        }
                        sentAny = true;
                        continue;
                    }

                    if ("CLICK_MOUSEKEYS_OFF".equalsIgnoreCase(token)) {
                        sendNamedKey("KP5");
                        Thread.sleep(300);
                        for (int i = 0; i < 5; i++) {
                            press((byte)0x04, (byte)0x00);
                            if (i < 4) Thread.sleep(120);
                        }
                        sentAny = true;
                        continue;
                    }

                    if ("CLEAR".equalsIgnoreCase(token)) {
                        Log.i(TAG, "MACRO clear field");
                        sendNamedKey("CMD_A");
                        Thread.sleep(120);
                        sendNamedKey("BACKSPACE");
                        sentAny = true;
                        continue;
                    }

                    String key = token.toUpperCase();
                    int repeat = 1;
                    int star = key.lastIndexOf('*');
                    if (star > 0) {
                        repeat = Integer.parseInt(key.substring(star + 1));
                        repeat = Math.max(1, Math.min(50, repeat));
                        key = key.substring(0, star);
                    }
                    for (int i = 0; i < repeat; i++) {
                        Log.i(TAG, "MACRO key " + key + " " + (i + 1) + "/" + repeat);
                        sendNamedKey(key);
                        sentAny = true;
                        if (repeat > 1) Thread.sleep(500);
                    }
                }
                publish("マクロ送信完了");
                Log.i(TAG, "MACRO complete sentAny=" + sentAny);
            } catch (Exception e) {
                Log.e(TAG, "MACRO failed afterOutput=" + sentAny + " macro=" + macro, e);
                publish("マクロ送信失敗" + (sentAny ? "（途中まで送信済み）" : ""));
            }
        });
    }

    private void typeAscii(String text) throws Exception {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                press(keyForDigit(c).code);
            } else if (c >= 'a' && c <= 'z') {
                press((byte)(0x04 + (c - 'a')));
            } else if (c >= 'A' && c <= 'Z') {
                press((byte)0x02, (byte)(0x04 + (c - 'A')));
            } else if (c == ' ') {
                press((byte)0x2C);
            } else if (c == '-') {
                press((byte)0x2D);
            } else if (c == '.') {
                press((byte)0x37);
            } else if (c == '/') {
                press((byte)0x38);
            } else {
                throw new IllegalArgumentException("unsupported TEXT char: " + c);
            }
            Thread.sleep(35);
        }
    }

    private void runFullFlow(String code) {
        if (!code.matches("[0-9]+")) {
            publish("実戦テスト停止：商品番号は数字のみ対応 " + code);
            return;
        }
        if (!connected || target == null || hid == null) {
            publish("実戦テスト不可：iPad未接続");
            return;
        }

        sender.execute(() -> {
            try {
                publish("実戦テスト開始: " + code);
                runFullFlowInternal(code);
                publish("実戦テスト完了：商品追加→伝票保存まで送信 " + code);
            } catch (Exception e) {
                Log.e(TAG, "FULL FLOW failed code=" + code, e);
                publish("実戦テスト失敗：" + e.getClass().getSimpleName());
            }
        });
    }

    private void runFullFlowInternal(String code) throws Exception {
        // 実機で成功確認済み：13桁入力 → Enter×2 → 商品候補表示
        typeBarcode(code);

        // 商品候補の描画とフルキーボードアクセスのフォーカス確定待ち
        Thread.sleep(1400);

        // 学習済み操作があれば、候補表示後から一時保存まで
        // ユーザーが実機で成功させたキー順序・待ち時間をそのまま再生する。
        String learned = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LEARNED_FLOW, "").trim();
        if (!learned.isEmpty()) {
            runLearnedFlowInternal(learned);
            return;
        }

        // 学習前のフォールバック。商品選択Tab回数のみ調整可能。
        int productSelectTabs = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_PRODUCT_SELECT_TABS, 2);
        productSelectTabs = Math.max(0, Math.min(8, productSelectTabs));
        for (int i = 0; i < productSelectTabs; i++) {
            sendNamedKey("TAB");
            Thread.sleep(420);
        }
        Thread.sleep(350);
        sendNamedKey("SPACE");

        Thread.sleep(1400);

        // 伝票一時保存は従来の確認済み経路。
        for (int i = 0; i < 3; i++) {
            sendNamedKey("TAB");
            Thread.sleep(420);
        }
        Thread.sleep(350);
        sendNamedKey("SPACE");

    }

    private void runLearnedFlowInternal(String macro) throws Exception {
        String[] tokens = macro.split(",");
        for (String raw : tokens) {
            String token = raw.trim().toUpperCase();
            if (token.isEmpty()) continue;

            if (token.startsWith("WAIT:")) {
                long ms = Long.parseLong(token.substring(5));
                ms = Math.max(80, Math.min(5000, ms));
                Thread.sleep(ms);
                continue;
            }

            // 学習再生では安全上、フォーカス移動と決定だけ許可。
            if ("ENTER".equals(token) || "TAB".equals(token) || "SHIFT_TAB".equals(token) || "SPACE".equals(token)) {
                sendNamedKey(token);
                continue;
            }

            throw new IllegalArgumentException("unsupported learned token: " + token);
        }
    }

    private void runOrderItemsFlow(JSONArray items) throws Exception {
        LearnedOrderTemplate learned = getLearnedOrderTemplate();

        if (items.length() > 1 && learned == null) {
            throw new IllegalStateException("MULTI_ITEM_TEMPLATE_REQUIRED");
        }

        if (learned == null) {
            JSONObject only = items.optJSONObject(0);
            if (only == null) throw new IllegalArgumentException("missing item");
            runFullFlowInternal(only.optString("airCode", ""));
            return;
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) throw new IllegalArgumentException("missing item " + i);
            String code = item.optString("airCode", "");
            if (!code.matches("[0-9]+")) throw new IllegalArgumentException("invalid air code " + i);

            publish("Airレジ入力 " + (i + 1) + "/" + items.length() + "："
                    + item.optString("displayName", item.optString("name", "")));

            // 学習記録はEnterから始まるため、ここでは数字だけ入力する。
            typeDigits(code);
            // 先頭Enter前は従来の実機安定値を確保。
            Thread.sleep(1800);

            String phaseMacro = (i < items.length() - 1)
                    ? learned.betweenItems
                    : learned.finalItem;

            if (i > 0) {
                // 実機確認で、2商品目以降は1商品目より商品タイルのフォーカスが
                // 1 Tabぶん手前から始まることを確認。保存済み記録そのものは変更せず、
                // 商品タイルを決定する最初のSPACE直前だけTabを1回減らす。
                phaseMacro = adjustAdditionalItemProductSelection(phaseMacro);
            }

            runLearnedFlowInternal(phaseMacro);
        }
    }

    private String adjustAdditionalItemProductSelection(String macro) {
        if (macro == null || macro.trim().isEmpty()) return macro;

        String[] raw = macro.split(",");
        List<String> tokens = new ArrayList<>();
        for (String part : raw) {
            String t = part == null ? "" : part.trim().toUpperCase();
            if (!t.isEmpty()) tokens.add(t);
        }

        int firstSpace = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if ("SPACE".equals(tokens.get(i))) {
                firstSpace = i;
                break;
            }
        }
        if (firstSpace < 0) return macro;

        int tabToRemove = -1;
        for (int i = firstSpace - 1; i >= 0; i--) {
            String t = tokens.get(i);
            if ("TAB".equals(t)) {
                tabToRemove = i;
                break;
            }
            if ("SPACE".equals(t)) break;
        }
        if (tabToRemove < 0) return macro;

        tokens.remove(tabToRemove);
        // そのTabのために記録された待ち時間も一緒に除く。
        if (tabToRemove < tokens.size() &&
                tokens.get(tabToRemove).startsWith("WAIT:")) {
            tokens.remove(tabToRemove);
        }

        return joinMacroTokens(tokens, 0, tokens.size());
    }

    private LearnedOrderTemplate getLearnedOrderTemplate() {
        String macro = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LEARNED_FLOW, "");
        if (macro == null || macro.trim().isEmpty()) return null;

        String[] raw = macro.split(",");
        List<String> tokens = new ArrayList<>();
        for (String part : raw) {
            String t = part == null ? "" : part.trim().toUpperCase();
            if (!t.isEmpty()) tokens.add(t);
        }
        if (tokens.size() < 8) return null;

        int firstPair = findEnterPairStart(tokens, 0);
        if (firstPair < 0) return null;
        int secondPair = findEnterPairStart(tokens, firstPair + 1);
        if (secondPair < 0 || secondPair <= firstPair) return null;

        String between = joinMacroTokens(tokens, firstPair, secondPair);
        String last = joinMacroTokens(tokens, secondPair, tokens.size());
        if (!between.contains("SPACE") || !last.contains("SPACE")) return null;

        return new LearnedOrderTemplate(between, last);
    }

    private int findEnterPairStart(List<String> tokens, int start) {
        for (int i = Math.max(0, start); i < tokens.size(); i++) {
            if (!"ENTER".equals(tokens.get(i))) continue;
            for (int j = i + 1; j < tokens.size(); j++) {
                String t = tokens.get(j);
                if (t.startsWith("WAIT:")) continue;
                if ("ENTER".equals(t)) return i;
                break;
            }
        }
        return -1;
    }

    private String joinMacroTokens(List<String> tokens, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (out.length() > 0) out.append(",");
            out.append(tokens.get(i));
        }
        return out.toString();
    }

    private static final class LearnedOrderTemplate {
        final String betweenItems;
        final String finalItem;
        LearnedOrderTemplate(String betweenItems, String finalItem) {
            this.betweenItems = betweenItems;
            this.finalItem = finalItem;
        }
    }

    private void prepareNextOrderManually() {
        // 安全優先：ここではiPadへキーを一切送らない。
        // スタッフがAirレジの検索欄を手動でタップした後に、
        // Cloudflare注文キューの停止だけを解除する。
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_NEEDS_NEXT_PREP, false).apply();
        lastBridgeNotice = "";
        publish("次の注文受付を再開しました。Airレジが商品番号入力位置になっていることを確認してください。");
    }

    private void pollBridgeQueue() {
        if (!connected || target == null || hid == null) return;
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_NEEDS_NEXT_PREP, false)) {
            publishBridgeNotice("伝票保存後の安全停止中。「次の注文準備」を押してください。");
            return;
        }
        if (!bridgeBusy.compareAndSet(false, true)) return;

        network.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device", Build.MODEL == null ? "Galaxy" : Build.MODEL);
                body.put("multiItemLearned", getLearnedOrderTemplate() != null);
                JSONObject response = httpJson("POST", bridgeBaseUrl() + "/api/bridge/claim", body);

                if (!response.optBoolean("ok", false)) {
                    bridgeBusy.set(false);
                    publishBridgeNotice("自動注文：Cloudflare応答エラー");
                    return;
                }

                if (response.optBoolean("blocked", false)) {
                    String reason = response.optString("reason", "");
                    if ("MAPPING_REQUIRED".equals(reason)) {
                        String name = "";
                        if (response.optJSONArray("missingMappings") != null &&
                                response.optJSONArray("missingMappings").length() > 0) {
                            name = response.optJSONArray("missingMappings")
                                    .optJSONObject(0).optString("displayName", "");
                        }
                        publishBridgeNotice("自動注文待機：Airレジ商品番号未登録 " + name);
                    } else if ("MULTI_ITEM_TEMPLATE_REQUIRED".equals(reason)) {
                        publishBridgeNotice("自動注文待機：複数商品用の実機記録が必要です。");
                    } else if ("TOO_MANY_ITEMS".equals(reason)) {
                        publishBridgeNotice("自動注文待機：1伝票の商品数が多すぎます。");
                    } else {
                        publishBridgeNotice("自動注文待機：" + reason);
                    }
                    bridgeBusy.set(false);
                    return;
                }

                JSONObject order = response.optJSONObject("order");
                if (order == null) {
                    bridgeBusy.set(false);
                    return;
                }

                String orderId = order.optString("id", "");
                String seat = order.optString("seat", "");
                JSONArray items = order.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    JSONObject legacyItem = order.optJSONObject("item");
                    if (legacyItem != null) {
                        items = new JSONArray();
                        items.put(legacyItem);
                    }
                }

                if (orderId.isEmpty() || items == null || items.length() == 0) {
                    bridgeBusy.set(false);
                    publishBridgeNotice("自動注文：受信データ不正");
                    return;
                }

                boolean validItems = true;
                String firstItemName = "";
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    String airCode = item == null ? "" : item.optString("airCode", "");
                    if (!airCode.matches("[0-9]+")) {
                        validItems = false;
                        break;
                    }
                    if (i == 0) {
                        firstItemName = item.optString("displayName", item.optString("name", ""));
                    }
                }
                if (!validItems) {
                    bridgeBusy.set(false);
                    publishBridgeNotice("自動注文：商品コード受信データ不正");
                    return;
                }

                final JSONArray orderItems = items;
                final String itemSummary = orderItems.length() == 1
                        ? firstItemName
                        : firstItemName + " ほか" + (orderItems.length() - 1) + "点";
                publish("注文受信 " + seat + " / " + itemSummary + " / " + orderItems.length() + "商品 → Airレジ入力開始");

                sender.execute(() -> {
                    try {
                        runOrderItemsFlow(orderItems);
                        network.execute(() -> {
                            try {
                                JSONObject done = new JSONObject();
                                done.put("device", Build.MODEL == null ? "Galaxy" : Build.MODEL);
                                httpJson("POST", bridgeBaseUrl() + "/api/bridge/orders/" + orderId + "/complete", done);
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .putBoolean(KEY_NEEDS_NEXT_PREP, true).apply();
                                lastBridgeNotice = "";
                                publish("注文完了 " + seat + " / " + itemSummary + "：伝票保存。次の注文位置まで記録再生済み。画面確認後に次の注文を許可してください。");
                            } catch (Exception ackError) {
                                Log.e(TAG, "bridge completion ack failed", ackError);
                                publish("伝票保存済み。ただしCloudflare完了通知に失敗：" + orderId);
                            } finally {
                                bridgeBusy.set(false);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "auto bridge UI flow failed", e);
                        network.execute(() -> {
                            try {
                                JSONObject failed = new JSONObject();
                                failed.put("device", Build.MODEL == null ? "Galaxy" : Build.MODEL);
                                failed.put("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                                httpJson("POST", bridgeBaseUrl() + "/api/bridge/orders/" + orderId + "/error", failed);
                            } catch (Exception reportError) {
                                Log.e(TAG, "bridge error report failed", reportError);
                            } finally {
                                bridgeBusy.set(false);
                                publish("自動注文停止扱い：" + seat + " / " + itemSummary + "（重複防止のため自動再試行しません）");
                            }
                        });
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "bridge poll failed", e);
                bridgeBusy.set(false);
                publishBridgeNotice("自動注文：Cloudflare接続待ち");
            }
        });
    }

    private String bridgeBaseUrl() {
        String url = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_BRIDGE_BASE_URL, DEFAULT_BRIDGE_BASE_URL);
        if (url == null) url = DEFAULT_BRIDGE_BASE_URL;
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("https://")) return DEFAULT_BRIDGE_BASE_URL;
        return url;
    }

    private void testBridgeApi() {
        network.execute(() -> {
            String base = bridgeBaseUrl();
            try {
                JSONObject response = httpJson("GET", base + "/api/bridge/status", null);
                if (response.optBoolean("ok", false)) {
                    publish("Bridge API接続OK：" + base);
                } else {
                    publish("Bridge API応答あり・エラー：" + base);
                }
            } catch (Exception e) {
                Log.e(TAG, "bridge api test failed", e);
                publish("Bridge API接続失敗：" + base);
            }
        });
    }

    private void publishBridgeNotice(String message) {
        if (message.equals(lastBridgeNotice)) return;
        lastBridgeNotice = message;
        publish(message);
    }

    private JSONObject httpJson(String method, String urlString, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setUseCaches(false);

        if (body != null) {
            conn.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        conn.disconnect();

        if (text.length() == 0) {
            JSONObject empty = new JSONObject();
            empty.put("ok", status >= 200 && status < 300);
            empty.put("status", status);
            return empty;
        }
        return new JSONObject(text.toString());
    }

    private void sendBarcodeOnly(String code) {
        if (!code.matches("[0-9]+")) {
            publish("バーコードは数字のみ対応: " + code);
            return;
        }
        if (!connected || target == null || hid == null) {
            publish("バーコード送信不可：iPad未接続");
            return;
        }
        sender.execute(() -> {
            try {
                typeDigits(code);
                publish("バーコード文字列のみ送信完了: " + code);
            } catch (Exception e) {
                publish("バーコード文字列送信失敗: " + code);
            }
        });
    }

    private void typeBarcode(String code) throws Exception {
        typeDigits(code);
        Thread.sleep(1800);
        press((byte)0x28); // Enter / Return
        Thread.sleep(1500);
        press((byte)0x28); // 実機で確認済みの2回目
    }

    private void typeDigits(String code) throws Exception {
        // Airレジの検索欄フォーカス確定を待つ。先頭桁の取りこぼし防止。
        Thread.sleep(700);
        for (int i=0;i<code.length();i++) {
            Key k = keyForDigit(code.charAt(i));
            press(k.code);
            Thread.sleep(70);
        }
    }

    private void press(byte keyCode) throws Exception {
        press((byte)0x00, keyCode);
    }

    private void press(byte modifier, byte keyCode) throws Exception {
        byte[] down = new byte[8];
        down[0] = modifier;
        down[2] = keyCode;
        byte[] up = new byte[8];

        if (!hid.sendReport(target, 0, down)) throw new IllegalStateException("key down failed");
        Thread.sleep(55);
        if (!hid.sendReport(target, 0, up)) throw new IllegalStateException("key up failed");
        Thread.sleep(55);
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
        Log.i(TAG, text);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String old = prefs.getString("status_history", "");
        String line = System.currentTimeMillis() + " | " + text;
        String history = old.isEmpty() ? line : old + "\n" + line;
        String[] lines = history.split("\n");
        if (lines.length > 25) {
            StringBuilder b = new StringBuilder();
            for (int i = lines.length - 25; i < lines.length; i++) {
                if (b.length() > 0) b.append("\n");
                b.append(lines[i]);
            }
            history = b.toString();
        }
        prefs.edit()
                .putString("last_status", text)
                .putString("status_history", history)
                .apply();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));

        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS, text);
        i.putExtra(EXTRA_HISTORY, history);
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
