# いっぷく Air Bridge

2Fのモバイルオーダーを、注文専用iPadのAirレジへ自動入力するためのAndroidブリッジです。

## v0.2.0

Bluetooth Classic HID（BluetoothHidDevice）方式へ変更しました。

- GalaxyをBluetoothバーコード入力デバイスとしてiPadへ直接接続
- 注文専用iPadを一度設定すれば自動再接続
- Foreground Serviceでバックグラウンド常駐
- 端末再起動後も自動起動
- 切断時は5秒後に自動再接続
- 運用用の「手動送信」ボタンは無し
- バーコード送信キューを実装済み

次の段階でCloudflareのモバイルオーダーキューを接続し、
注文受信 → 商品バーコード自動入力 → Airレジ伝票の一時保存
まで無人化します。

> Airレジの非公開APIは使用しません。2台目iPadを注文専用端末として使う前提です。
