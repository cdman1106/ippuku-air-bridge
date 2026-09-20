package jp.ippuku.airbridge;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.OutputStream;

public class UsbHidDiagnosticsActivity extends Activity {

    private TextView result;
    private Button sendTest;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int p = dp(16);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("USB有線HID 診断");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        body.addView(title);

        result = new TextView(this);
        result.setPadding(0,p,0,p);
        body.addView(result);

        Button diagnose = new Button(this);
        diagnose.setText("このAndroidがUSBバーコードリーダー化できるか診断");
        diagnose.setOnClickListener(v -> diagnose());
        body.addView(diagnose);

        sendTest = new Button(this);
        sendTest.setText("USBで TEST123 + Enter を送信");
        sendTest.setEnabled(false);
        sendTest.setOnClickListener(v -> sendTest123());
        body.addView(sendTest);

        TextView note = new TextView(this);
        note.setText("\nこの画面はUSB Gadget/HIDの可否だけを調べます。/dev/hidg0 が存在し、rootから書き込める場合のみ送信テストを有効にします。USB構成自体は勝手に変更しません。");
        body.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        setContentView(sv);

        diagnose();
    }

    private void diagnose() {
        new Thread(() -> {
            StringBuilder s = new StringBuilder();
            boolean root = commandOk("su -c id");
            boolean configA = new File("/config/usb_gadget").exists();
            boolean configB = new File("/sys/kernel/config/usb_gadget").exists();
            boolean udc = new File("/sys/class/udc").exists();
            boolean hidg0 = new File("/dev/hidg0").exists();

            s.append("root権限: ").append(root ? "あり" : "なし").append("\n");
            s.append("/config/usb_gadget: ").append(configA ? "あり" : "なし").append("\n");
            s.append("/sys/kernel/config/usb_gadget: ").append(configB ? "あり" : "なし").append("\n");
            s.append("/sys/class/udc: ").append(udc ? "あり" : "なし").append("\n");
            s.append("/dev/hidg0: ").append(hidg0 ? "あり" : "なし").append("\n\n");

            if (hidg0 && root) {
                s.append("判定: USB HID送信を試せます。\n");
            } else if ((configA || configB) && udc && root) {
                s.append("判定: カーネル側にUSB Gadget機能はあります。HID Gadgetの作成処理を追加すれば試せる可能性があります。\n");
            } else if (!root) {
                s.append("判定: 通常アプリの権限ではAndroidをUSBキーボード化できません。rootが必要な可能性が高いです。\n");
            } else {
                s.append("判定: この端末の現在のカーネル構成ではUSB HID化が難しい可能性があります。\n");
            }

            boolean canSend = hidg0 && root;
            runOnUiThread(() -> {
                result.setText(s.toString());
                sendTest.setEnabled(canSend);
            });
        }).start();
    }

    private boolean commandOk(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh","-c",cmd});
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendTest123() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su","-c","cat > /dev/hidg0"});
                OutputStream out = p.getOutputStream();

                type(out, "TEST123");
                press(out, (byte)0x28, (byte)0x00); // Enter

                out.flush();
                out.close();
                int code = p.waitFor();

                runOnUiThread(() -> result.append("\nUSB送信終了 code=" + code + "。iPad側の入力欄を確認してください。\n"));
            } catch (Exception e) {
                runOnUiThread(() -> result.append("\nUSB送信エラー: " + e.getMessage() + "\n"));
            }
        }).start();
    }

    private void type(OutputStream out, String text) throws Exception {
        for (int i=0;i<text.length();i++) {
            Key k=keyFor(text.charAt(i));
            if (k == null) throw new IllegalArgumentException("unsupported char");
            press(out,k.code,k.mod);
            Thread.sleep(25);
        }
    }

    private void press(OutputStream out, byte code, byte mod) throws Exception {
        byte[] down=new byte[8];
        down[0]=mod;
        down[2]=code;
        out.write(down);
        out.flush();
        Thread.sleep(30);

        byte[] up=new byte[8];
        out.write(up);
        out.flush();
        Thread.sleep(30);
    }

    private Key keyFor(char c) {
        if (c >= '1' && c <= '9') return new Key((byte)(0x1E + (c - '1')), (byte)0);
        if (c == '0') return new Key((byte)0x27,(byte)0);
        if (c >= 'a' && c <= 'z') return new Key((byte)(0x04 + (c - 'a')), (byte)0);
        if (c >= 'A' && c <= 'Z') return new Key((byte)(0x04 + (c - 'A')), (byte)0x02);
        return null;
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Key {
        final byte code;
        final byte mod;
        Key(byte code, byte mod) { this.code=code; this.mod=mod; }
    }
}
