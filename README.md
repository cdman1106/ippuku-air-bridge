# いっぷく Air Bridge

Android端末をBluetooth HIDキーボードとしてiPadへ接続し、Airレジの商品検索欄へ「商品バーコード番号 + Enter」を送る実機検証アプリです。

## テスト手順

1. Airレジの商品にバーコード番号（例: `100001`）を登録
2. GitHub Actionsで生成されたDebug APKをAndroid端末へインストール
3. アプリでBluetooth HIDを開始し、iPadからペアリング
4. Airレジの注文入力画面で商品検索欄を選択
5. Air Bridgeから `100001` を送信
6. 対象商品がAirレジ伝票へ追加されるか確認

複数コードは1行1コードで入力して連続送信できます。

> Airレジの非公開APIにはアクセスしません。Bluetooth HIDキーボード入力のみを使う検証用プロジェクトです。
