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
import android.widget.EditText;
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
    private EditText testBarcode;
    private EditText testMacro;
    private EditText quickDelayMs;
    private EditText quickTabCount;
    private EditText quickTabGapMs;
    private EditText mouseXSteps;
    private EditText mouseYSteps;
    private TextView historyView;
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
        restoreTestSettings();
        refreshHistory();
    }

    @Override protected void onPause() {
        super.onPause();
        saveTestSettings();
    }

    private void saveTestSettings() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (testBarcode != null) e.putString("test_barcode", testBarcode.getText().toString());
        if (quickDelayMs != null) e.putString("test_delay", quickDelayMs.getText().toString());
        if (quickTabCount != null) e.putString("test_tab_count", quickTabCount.getText().toString());
        if (quickTabGapMs != null) e.putString("test_tab_gap", quickTabGapMs.getText().toString());
        if (mouseXSteps != null) e.putString("mouse_x_steps", mouseXSteps.getText().toString());
        if (mouseYSteps != null) e.putString("mouse_y_steps", mouseYSteps.getText().toString());
        e.apply();
    }

    private void restoreTestSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (testBarcode != null) testBarcode.setText(p.getString("test_barcode", "4944496690023"));
        if (quickDelayMs != null) quickDelayMs.setText(p.getString("test_delay", "3000"));
        if (quickTabCount != null) quickTabCount.setText(p.getString("test_tab_count", "1"));
        if (quickTabGapMs != null) quickTabGapMs.setText(p.getString("test_tab_gap", "700"));
        if (mouseXSteps != null) mouseXSteps.setText(p.getString("mouse_x_steps", "600"));
        if (mouseYSteps != null) mouseYSteps.setText(p.getString("mouse_y_steps", "400"));
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

        TextView simpleTitle = new TextView(this);
        simpleTitle.setText("かんたんテスト");
        simpleTitle.setTextSize(22);
        simpleTitle.setPadding(0, dp(20), 0, dp(8));
        body.addView(simpleTitle);

        TextView simpleHelp = new TextView(this);
        simpleHelp.setText("基本はこの4ボタンだけ使えばOKです。");
        simpleHelp.setPadding(0, 0, 0, dp(8));
        body.addView(simpleHelp);

        testBarcode = new EditText(this);
        testBarcode.setSingleLine(true);
        testBarcode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        testBarcode.setText("4944496690023");
        testBarcode.setHint("テスト用バーコード");
        body.addView(testBarcode);

        LinearLayout quickSettings = new LinearLayout(this);
        quickSettings.setOrientation(LinearLayout.HORIZONTAL);

        quickDelayMs = new EditText(this);
        quickDelayMs.setSingleLine(true);
        quickDelayMs.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        quickDelayMs.setText("2500");
        quickDelayMs.setHint("検索待ち時間(ms)");
        quickSettings.addView(quickDelayMs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        quickTabCount = new EditText(this);
        quickTabCount.setSingleLine(true);
        quickTabCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        quickTabCount.setText("1");
        quickTabCount.setHint("Tab回数");
        quickSettings.addView(quickTabCount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        quickTabGapMs = new EditText(this);
        quickTabGapMs.setSingleLine(true);
        quickTabGapMs.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        quickTabGapMs.setText("700");
        quickTabGapMs.setHint("Tab間隔(ms)");
        quickSettings.addView(quickTabGapMs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(quickSettings);

        Button quickSearch = new Button(this);
        quickSearch.setText("① 商品候補を表示");
        quickSearch.setTextSize(18);
        quickSearch.setMinHeight(dp(56));
        quickSearch.setOnClickListener(v -> runQuickSearch());
        body.addView(quickSearch);

        Button oneShotDiag = new Button(this);
        oneShotDiag.setText("一発診断（約20秒・決定しない）");
        oneShotDiag.setTextSize(18);
        oneShotDiag.setMinHeight(dp(60));
        oneShotDiag.setOnClickListener(v -> runOneShotDiagnostic());
        body.addView(oneShotDiag);

        TextView oneShotHelp = new TextView(this);
        oneShotHelp.setText("検索→2秒待機→Tabを1秒ごとに12回。Space/Enterは押さないので、商品追加や会計操作はしません。iPadを画面録画してこのボタンを1回押せば、フォーカス経路をまとめて確認できます。");
        oneShotHelp.setPadding(0, 0, 0, dp(10));
        body.addView(oneShotHelp);

        TextView mouseKeysTitle = new TextView(this);
        mouseKeysTitle.setText("Bluetoothマウステスト（本命）");
        mouseKeysTitle.setTextSize(18);
        mouseKeysTitle.setPadding(0, dp(12), 0, dp(4));
        body.addView(mouseKeysTitle);

        TextView mouseKeysHelp = new TextView(this);
        mouseKeysHelp.setText("GalaxyをBluetoothキーボード＋マウスとして直接送信します。iPadの 設定 → アクセシビリティ → タッチ → AssistiveTouch → マウスキー はOFFにしてください。数字入力とポインター操作は別Report IDで送ります。");
        mouseKeysHelp.setPadding(0, 0, 0, dp(6));
        body.addView(mouseKeysHelp);

        LinearLayout mkRow1 = new LinearLayout(this);
        mkRow1.setOrientation(LinearLayout.HORIZONTAL);
        Button mkUp = new Button(this);
        mkUp.setText("↑ 40");
        mkUp.setOnClickListener(v -> sendMacro("MOVE:0:-40"));
        mkRow1.addView(mkUp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button mkClick = new Button(this);
        mkClick.setText("左クリック");
        mkClick.setOnClickListener(v -> sendMacro("CLICK"));
        mkRow1.addView(mkClick, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(mkRow1);

        LinearLayout mkRow2 = new LinearLayout(this);
        mkRow2.setOrientation(LinearLayout.HORIZONTAL);
        Button mkLeft = new Button(this);
        mkLeft.setText("← 40");
        mkLeft.setOnClickListener(v -> sendMacro("MOVE:-40:0"));
        mkRow2.addView(mkLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button mkDown = new Button(this);
        mkDown.setText("↓ 40");
        mkDown.setOnClickListener(v -> sendMacro("MOVE:0:40"));
        mkRow2.addView(mkDown, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button mkRight = new Button(this);
        mkRight.setText("→ 40");
        mkRight.setOnClickListener(v -> sendMacro("MOVE:40:0"));
        mkRow2.addView(mkRight, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(mkRow2);

        Button mkHome = new Button(this);
        mkHome.setText("ポインターを左上へリセット");
        mkHome.setOnClickListener(v -> runMouseKeyHome());
        body.addView(mkHome);

        LinearLayout mkCoords = new LinearLayout(this);
        mkCoords.setOrientation(LinearLayout.HORIZONTAL);

        mouseXSteps = new EditText(this);
        mouseXSteps.setSingleLine(true);
        mouseXSteps.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        mouseXSteps.setText("600");
        mouseXSteps.setHint("右移動量");
        mkCoords.addView(mouseXSteps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mouseYSteps = new EditText(this);
        mouseYSteps.setSingleLine(true);
        mouseYSteps.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        mouseYSteps.setText("400");
        mouseYSteps.setHint("下移動量");
        mkCoords.addView(mouseYSteps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(mkCoords);

        LinearLayout mkCalRow = new LinearLayout(this);
        mkCalRow.setOrientation(LinearLayout.HORIZONTAL);

        Button mkMoveOnly = new Button(this);
        mkMoveOnly.setText("左上→指定位置へ移動");
        mkMoveOnly.setOnClickListener(v -> runMouseKeyMove(false));
        mkCalRow.addView(mkMoveOnly, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button mkMoveClick = new Button(this);
        mkMoveClick.setText("移動→左クリック");
        mkMoveClick.setOnClickListener(v -> runMouseKeyMove(true));
        mkCalRow.addView(mkMoveClick, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(mkCalRow);

        Button mkSearchClick = new Button(this);
        mkSearchClick.setText("検索→指定位置クリック（一括）");
        mkSearchClick.setOnClickListener(v -> runSearchThenMouseKeyClick());
        body.addView(mkSearchClick);

        LinearLayout simpleRow = new LinearLayout(this);
        simpleRow.setOrientation(LinearLayout.HORIZONTAL);

        Button quickPrev = new Button(this);
        quickPrev.setText("← 戻る");
        quickPrev.setTextSize(17);
        quickPrev.setOnClickListener(v -> sendKey("SHIFT_TAB"));
        simpleRow.addView(quickPrev, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button quickNext = new Button(this);
        quickNext.setText("② 次へ");
        quickNext.setTextSize(17);
        quickNext.setOnClickListener(v -> sendKey("TAB"));
        simpleRow.addView(quickNext, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button quickSelect = new Button(this);
        quickSelect.setText("③ 決定");
        quickSelect.setTextSize(17);
        quickSelect.setOnClickListener(v -> sendKey("SPACE"));
        simpleRow.addView(quickSelect, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(simpleRow);

        Button quickReset = new Button(this);
        quickReset.setText("やり直す（検索欄を空にする）");
        quickReset.setOnClickListener(v -> sendMacro("CLEAR"));
        body.addView(quickReset);

        Button autoFocusWalk = new Button(this);
        autoFocusWalk.setText("フォーカスをゆっくり10個進める");
        autoFocusWalk.setOnClickListener(v -> runTabWalk());
        body.addView(autoFocusWalk);

        Button quickAddSpace = new Button(this);
        quickAddSpace.setText("商品追加まで一括テスト（Space決定）");
        quickAddSpace.setMinHeight(dp(56));
        quickAddSpace.setOnClickListener(v -> runQuickAdd("SPACE"));
        body.addView(quickAddSpace);

        Button quickAddEnter = new Button(this);
        quickAddEnter.setText("商品追加まで一括テスト（Enter決定）");
        quickAddEnter.setOnClickListener(v -> runQuickAdd("ENTER"));
        body.addView(quickAddEnter);

        historyView = new TextView(this);
        historyView.setTextSize(13);
        historyView.setPadding(0, dp(16), 0, dp(8));
        historyView.setText("操作履歴：まだありません");
        body.addView(historyView);

        Button clearHistory = new Button(this);
        clearHistory.setText("操作履歴を消す");
        clearHistory.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("status_history")
                    .apply();
            refreshHistory();
        });
        body.addView(clearHistory);

        TextView advancedHint = new TextView(this);
        advancedHint.setText("↓ ここから下は必要な時だけ使う詳細テスト");
        advancedHint.setPadding(0, dp(18), 0, dp(4));
        body.addView(advancedHint);

        TextView diagTitle = new TextView(this);
        diagTitle.setText("詳細テスト（必要な時だけ）");
        diagTitle.setTextSize(20);
        diagTitle.setPadding(0, dp(20), 0, dp(8));
        body.addView(diagTitle);

        TextView diagHelp = new TextView(this);
        diagHelp.setText("Termux不要。ここからAirレジへのキー送信を確認できます。最終運用ではこの操作は使いません。");
        diagHelp.setPadding(0, 0, 0, dp(8));
        body.addView(diagHelp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button sendOnly = new Button(this);
        sendOnly.setText("数字だけ");
        sendOnly.setOnClickListener(v -> sendBarcodeOnly());
        row1.addView(sendOnly, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button search = new Button(this);
        search.setText("検索まで");
        search.setOnClickListener(v -> sendBarcodeSearch());
        row1.addView(search, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        Button enter = new Button(this);
        enter.setText("Enter");
        enter.setOnClickListener(v -> sendKey("ENTER"));
        row2.addView(enter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button tab = new Button(this);
        tab.setText("Tab");
        tab.setOnClickListener(v -> sendKey("TAB"));
        row2.addView(tab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button shiftTab = new Button(this);
        shiftTab.setText("Shift+Tab");
        shiftTab.setOnClickListener(v -> sendKey("SHIFT_TAB"));
        row2.addView(shiftTab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button space = new Button(this);
        space.setText("Space");
        space.setOnClickListener(v -> sendKey("SPACE"));
        row2.addView(space, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);

        Button up = new Button(this);
        up.setText("↑");
        up.setOnClickListener(v -> sendKey("UP"));
        row3.addView(up, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button down = new Button(this);
        down.setText("↓");
        down.setOnClickListener(v -> sendKey("DOWN"));
        row3.addView(down, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button left = new Button(this);
        left.setText("←");
        left.setOnClickListener(v -> sendKey("LEFT"));
        row3.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button right = new Button(this);
        right.setText("→");
        right.setOnClickListener(v -> sendKey("RIGHT"));
        row3.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(row3);

        Button clear = new Button(this);
        clear.setText("検索欄を全消去");
        clear.setOnClickListener(v -> sendMacro("CLEAR"));
        body.addView(clear);

        testMacro = new EditText(this);
        testMacro.setSingleLine(false);
        testMacro.setMinLines(2);
        testMacro.setText("CLEAR,TEXT:4944496690023,WAIT:2500,ENTER");
        testMacro.setHint("例: CLEAR,TEXT:4944496690023,WAIT:2500,ENTER,TAB*3,SPACE");
        body.addView(testMacro);

        Button runMacro = new Button(this);
        runMacro.setText("マクロ実行");
        runMacro.setOnClickListener(v -> sendMacro(testMacro.getText().toString()));
        body.addView(runMacro);

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

    private int readInt(EditText e, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String currentBarcode() {
        return testBarcode == null ? "" : testBarcode.getText().toString().trim();
    }

    private String buildRepeatedKey(String key, int count, int gapMs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (b.length() > 0) b.append(",");
            b.append(key);
            if (i < count - 1 && gapMs > 0) b.append(",WAIT:").append(gapMs);
        }
        return b.toString();
    }

    private void runMouseKeyHome() {
        sendMacro("MOUSE_HOME");
    }

    private String buildMouseMoveMacro(boolean click) {
        int x = readInt(mouseXSteps, 600, 0, 2000);
        int y = readInt(mouseYSteps, 400, 0, 2000);
        StringBuilder m = new StringBuilder();
        m.append("MOUSE_HOME,WAIT:200,MOVE:").append(x).append(":").append(y);
        if (click) m.append(",WAIT:250,CLICK");
        return m.toString();
    }

    private void runMouseKeyMove(boolean click) {
        sendMacro(buildMouseMoveMacro(click));
    }

    private void runSearchThenMouseKeyClick() {
        String code = currentBarcode();
        if (code.isEmpty()) {
            toast("バーコードを入力してください。");
            return;
        }
        int wait = readInt(quickDelayMs, 3000, 1000, 10000);
        String macro = "CLEAR,WAIT:500,TEXT:" + code + ",WAIT:" + wait +
                ",ENTER,WAIT:600,ENTER,WAIT:1500," + buildMouseMoveMacro(true);
        sendMacro(macro);
    }

    private void runOneShotDiagnostic() {
        String code = currentBarcode();
        if (code.isEmpty()) {
            toast("バーコードを入力してください。");
            return;
        }
        int wait = readInt(quickDelayMs, 3000, 1000, 10000);
        StringBuilder m = new StringBuilder();
        m.append("CLEAR,WAIT:500,TEXT:").append(code)
                .append(",WAIT:").append(wait)
                .append(",ENTER,WAIT:600,ENTER,WAIT:2000");
        for (int i = 0; i < 12; i++) {
            m.append(",TAB");
            if (i < 11) m.append(",WAIT:1000");
        }
        sendMacro(m.toString());
        toast("一発診断を開始しました。iPad画面をそのまま見てください。");
    }

    private void runQuickSearch() {
        String code = currentBarcode();
        if (code.isEmpty()) {
            toast("バーコードを入力してください。");
            return;
        }
        int wait = readInt(quickDelayMs, 2500, 0, 10000);
        sendMacro("CLEAR,WAIT:300,TEXT:" + code + ",WAIT:" + wait + ",ENTER,WAIT:600,ENTER");
    }

    private void runTabWalk() {
        int count = readInt(quickTabCount, 10, 1, 30);
        int gap = readInt(quickTabGapMs, 700, 100, 3000);
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (m.length() > 0) m.append(",");
            m.append("TAB");
            if (i < count - 1) m.append(",WAIT:").append(gap);
        }
        sendMacro(m.toString());
    }

    private void runQuickAdd(String activateKey) {
        String code = currentBarcode();
        if (code.isEmpty()) {
            toast("バーコードを入力してください。");
            return;
        }
        int wait = readInt(quickDelayMs, 2500, 0, 10000);
        int count = readInt(quickTabCount, 1, 0, 30);
        int gap = readInt(quickTabGapMs, 700, 100, 3000);

        StringBuilder m = new StringBuilder();
        m.append("CLEAR,WAIT:300,TEXT:").append(code)
                .append(",WAIT:").append(wait)
                .append(",ENTER,WAIT:600,ENTER,WAIT:1000");
        for (int i = 0; i < count; i++) {
            m.append(",TAB,WAIT:").append(gap);
        }
        m.append(",").append(activateKey);
        sendMacro(m.toString());
    }

    private void refreshHistory() {
        if (historyView == null) return;
        String h = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("status_history", "");
        if (h == null || h.trim().isEmpty()) {
            historyView.setText("操作履歴：まだありません");
            return;
        }
        String[] lines = h.split("\\n");
        StringBuilder out = new StringBuilder("操作履歴（新しい順）\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            int sep = line.indexOf(" | ");
            out.append("・").append(sep >= 0 ? line.substring(sep + 3) : line).append("\n");
        }
        historyView.setText(out.toString().trim());
    }

    private void sendBarcodeOnly() {
        String code = testBarcode == null ? "" : testBarcode.getText().toString().trim();
        if (code.isEmpty()) {
            toast("バーコードを入力してください。");
            return;
        }
        Intent svc = new Intent(this, BridgeService.class);
        svc.setAction(BridgeService.ACTION_SEND_BARCODE_ONLY);
        svc.putExtra(BridgeService.EXTRA_CODE, code);
        startForegroundCompat(svc);
    }

    private void sendBarcodeSearch() {
        String code = testBarcode == null ? "" : testBarcode.getText().toString().trim();
        if (code.isEmpty()) {
            toast("バーコードを入力してください。");
            return;
        }
        Intent svc = new Intent(this, BridgeService.class);
        svc.setAction(BridgeService.ACTION_SEND_BARCODE);
        svc.putExtra(BridgeService.EXTRA_CODE, code);
        startForegroundCompat(svc);
    }

    private void sendKey(String key) {
        Intent svc = new Intent(this, BridgeService.class);
        svc.setAction(BridgeService.ACTION_SEND_KEY);
        svc.putExtra(BridgeService.EXTRA_KEY, key);
        startForegroundCompat(svc);
    }

    private void sendMacro(String macro) {
        if (macro == null || macro.trim().isEmpty()) {
            toast("マクロを入力してください。");
            return;
        }
        Intent svc = new Intent(this, BridgeService.class);
        svc.setAction(BridgeService.ACTION_SEND_MACRO);
        svc.putExtra(BridgeService.EXTRA_MACRO, macro.trim());
        startForegroundCompat(svc);
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
                refreshHistory();
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
