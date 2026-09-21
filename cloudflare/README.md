# いっぷく Air Bridge 専用 Worker

お客様向け注文画面 `ippuku-kanri` とは完全に分離した Air Bridge 専用 Cloudflare Worker です。

## 目的

- 注文画面のHTML / JS / Workerを変更せずに Air Bridge のAPIだけ更新する
- 同じ D1 `ippuku-orders` を読み書きする
- 1注文内の複数商品・複数量を Galaxy に返す
- Airレジ会計処理は行わない
- UI操作失敗時は自動再試行せず、重複伝票を防ぐ

## Deploy

リポジトリルートから:

```bash
npx wrangler deploy --config cloudflare/wrangler.jsonc
```

想定URL:

`https://ippuku-air-bridge-api.cdman1106.workers.dev`

デプロイ後、Galaxy の Air Bridge で「Bridge API URL」に上記URLを保存し、「接続確認」を押します。
