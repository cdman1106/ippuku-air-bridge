const AIR_BUILTIN_MAP = {
  "紅茶/アイスティー": {"@HOT":"2000000001128","@ICE":"2000000001135"},
  "ミルクティー": {"@HOT":"2000000001142","@ICE":"2000000001159"},
  "抹茶ラテ": {"@HOT":"2000000001180","@ICE":"2000000001197"},
  "ルイボスティー": {"@HOT":"2000000001371","@ICE":"2000000001388"},
  "ゆず蜜": {"@SODA":"2000000001272","@HOT_WATER":"2000000001289","@WATER":"2000000001296"},
  "コーヒー": {"@HOT":"2000000001081","@ICE":"2000000001098"},
  "カフェラテ": {"@HOT":"2000000001104","@ICE":"2000000001111"},
  "ウィンナーコーヒー": {"@HOT":"2000000001227","@ICE":"2000000001234"},
  "キャラメルマキアート": {"@HOT":"2000000001203","@ICE":"2000000001210"},
  "ホワイトモカ": {"":"2000000001241"},
  "カフェ・モカ": {"@HOT":"2000000001258","@ICE":"2000000001265"},
  "チョコチーノ": {"@HOT":"2000000001166","@ICE":"2000000001173"},
  "コーラ": {"":"2000000000237"},
  "みかんジュース": {"":"2000000000176"},
  "ペリエ": {"":"2000000000183"},
  "モンスター": {"":"2000000000190"},
  "ポパイサンド": {"":"2000000001357"},
  "あんバターサンド": {"":"2000000001364"},
  "チーズケーキ": {"":"2000000001302"},
  "コーヒーゼリーパフェ": {"":"2000000001395"},
  "こんがりワッフル": {"@CHOCO":"2000000001319","@CARAMEL":"2000000001326","@BERRY":"2000000001333"},
  "濃厚バニラアイス": {"@SINGLE":"2000000001340"},
  "ナッツ": {"":"2000000000251"}
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store"
    }
  });
}

function builtinOptionKey(name, optionText) {
  const text = String(optionText || "");
  const upper = text.toUpperCase();

  if ([
    "紅茶/アイスティー","ミルクティー","抹茶ラテ","ルイボスティー",
    "コーヒー","カフェラテ","ウィンナーコーヒー",
    "キャラメルマキアート","カフェ・モカ","チョコチーノ"
  ].includes(name)) {
    if (upper.includes("HOT")) return "@HOT";
    if (upper.includes("ICE")) return "@ICE";
    return "";
  }
  if (name === "ゆず蜜") {
    if (text.includes("炭酸割り")) return "@SODA";
    if (text.includes("お湯割り")) return "@HOT_WATER";
    if (text.includes("水割り")) return "@WATER";
    return "";
  }
  if (name === "こんがりワッフル") {
    if (text.includes("チョコ")) return "@CHOCO";
    if (text.includes("キャラメル")) return "@CARAMEL";
    if (text.includes("ベリー")) return "@BERRY";
    return "";
  }
  if (name === "濃厚バニラアイス") {
    if (text.includes("シングル")) return "@SINGLE";
    return "";
  }
  return "";
}

async function tableColumns(env, table) {
  const result = await env.DB.prepare("PRAGMA table_info(" + table + ")").all();
  return new Set((result.results || []).map((row) => String(row.name)));
}

async function addColumnIfMissing(env, table, columns, name, sql) {
  if (columns.has(name)) return;
  await env.DB.prepare("ALTER TABLE " + table + " ADD COLUMN " + sql).run();
  columns.add(name);
}

async function ensureBridgeSchema(env) {
  const cols = await tableColumns(env, "orders");
  await addColumnIfMissing(env, "orders", cols, "bridge_status", "bridge_status TEXT NOT NULL DEFAULT 'legacy'");
  await addColumnIfMissing(env, "orders", cols, "bridge_device", "bridge_device TEXT NOT NULL DEFAULT ''");
  await addColumnIfMissing(env, "orders", cols, "bridge_claimed_at", "bridge_claimed_at TEXT NOT NULL DEFAULT ''");
  await addColumnIfMissing(env, "orders", cols, "bridge_completed_at", "bridge_completed_at TEXT NOT NULL DEFAULT ''");
  await addColumnIfMissing(env, "orders", cols, "bridge_error", "bridge_error TEXT NOT NULL DEFAULT ''");

  await env.DB.prepare(
    "CREATE TABLE IF NOT EXISTS air_product_map (name TEXT NOT NULL, option_text TEXT NOT NULL DEFAULT '', air_code TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(name, option_text))"
  ).run();
  await env.DB.prepare(
    "CREATE INDEX IF NOT EXISTS idx_orders_bridge_status ON orders(bridge_status, created_at)"
  ).run();
}

function currentBusinessDayStartIso(date = new Date()) {
  const jst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  const y = jst.getUTCFullYear();
  const m = jst.getUTCMonth();
  const d = jst.getUTCDate();
  const h = jst.getUTCHours();
  const base = Date.UTC(y, m, h < 12 ? d - 1 : d, 3, 0, 0);
  return new Date(base).toISOString();
}

async function getOrder(env, id) {
  const row = await env.DB.prepare(
    "SELECT id, seat, status, total, note, created_at, updated_at, bridge_status FROM orders WHERE id = ?"
  ).bind(id).first();
  if (!row) return null;

  const items = await env.DB.prepare(
    "SELECT name, display_name, category, price, qty, option_text FROM order_items WHERE order_id = ? ORDER BY id ASC"
  ).bind(id).all();

  return {
    id: row.id,
    seat: row.seat,
    status: row.status,
    total: Number(row.total || 0),
    note: row.note || "",
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    bridgeStatus: row.bridge_status || "legacy",
    items: (items.results || []).map((x) => ({
      name: x.name,
      displayName: x.display_name || x.name,
      category: x.category || "",
      price: Number(x.price || 0),
      qty: Number(x.qty || 1),
      option: x.option_text || ""
    }))
  };
}

async function resolveAirCode(env, name, optionText) {
  const exact = await env.DB.prepare(
    "SELECT air_code FROM air_product_map WHERE name = ? AND option_text = ?"
  ).bind(name, optionText || "").first();
  if (exact?.air_code) return String(exact.air_code).replace(/^#/, "");

  const builtins = AIR_BUILTIN_MAP[name];
  if (builtins) {
    const key = builtinOptionKey(name, optionText);
    if (key && builtins[key]) return builtins[key];
    if (builtins[""]) return builtins[""];
  }

  if (optionText) {
    const fallback = await env.DB.prepare(
      "SELECT air_code FROM air_product_map WHERE name = ? AND option_text = ''"
    ).bind(name).first();
    if (fallback?.air_code) return String(fallback.air_code).replace(/^#/, "");
  }
  return "";
}

async function claim(request, env) {
  const body = await request.json().catch(() => ({}));
  const device = String(body?.device || "galaxy").trim().slice(0, 80) || "galaxy";
  const multiItemLearned = body?.multiItemLearned === true;
  const since = currentBusinessDayStartIso();

  const row = await env.DB.prepare(
    "SELECT id FROM orders WHERE status = 'ordered' AND bridge_status = 'pending' AND created_at >= ? ORDER BY created_at ASC LIMIT 1"
  ).bind(since).first();

  if (!row) return json({ ok: true, order: null });

  const order = await getOrder(env, row.id);
  if (!order) return json({ ok: true, order: null });

  const expanded = [];
  const missingMappings = [];

  for (const item of order.items || []) {
    if (item.category === "Fee") continue;

    const code = await resolveAirCode(env, item.name, item.option || "");
    if (!code) {
      missingMappings.push({
        name: item.name,
        option: item.option || "",
        displayName: item.displayName || item.name
      });
      continue;
    }
    for (let i = 0; i < Math.max(1, Number(item.qty || 1)); i++) {
      expanded.push({
        name: item.name,
        displayName: item.displayName || item.name,
        option: item.option || "",
        airCode: code
      });
    }
  }

  if (missingMappings.length) {
    return json({
      ok: true,
      blocked: true,
      reason: "MAPPING_REQUIRED",
      orderId: order.id,
      seat: order.seat,
      missingMappings
    });
  }

  if (!expanded.length) {
    return json({
      ok: true,
      blocked: true,
      reason: "NO_AIR_ITEMS",
      orderId: order.id,
      seat: order.seat
    });
  }

  if (expanded.length > 1 && !multiItemLearned) {
    return json({
      ok: true,
      blocked: true,
      reason: "MULTI_ITEM_TEMPLATE_REQUIRED",
      orderId: order.id,
      seat: order.seat,
      itemCount: expanded.length
    });
  }

  if (expanded.length > 20) {
    return json({
      ok: true,
      blocked: true,
      reason: "TOO_MANY_ITEMS",
      orderId: order.id,
      seat: order.seat,
      itemCount: expanded.length
    });
  }

  const now = new Date().toISOString();
  const claimed = await env.DB.prepare(
    "UPDATE orders SET bridge_status = 'processing', bridge_device = ?, bridge_claimed_at = ?, bridge_error = '', updated_at = ? WHERE id = ? AND bridge_status = 'pending'"
  ).bind(device, now, now, order.id).run();

  if (!claimed.meta?.changes) return json({ ok: true, order: null });

  return json({
    ok: true,
    order: {
      id: order.id,
      seat: order.seat,
      createdAt: order.createdAt,
      itemCount: expanded.length,
      items: expanded,
      item: expanded[0]
    }
  });
}

async function finish(request, env, id, success) {
  const body = await request.json().catch(() => ({}));
  const device = String(body?.device || "").trim().slice(0, 80);
  const error = String(body?.error || "").trim().slice(0, 300);
  const now = new Date().toISOString();

  let result;
  if (success) {
    result = await env.DB.prepare(
      "UPDATE orders SET bridge_status = 'completed', bridge_completed_at = ?, bridge_error = '', updated_at = ? WHERE id = ? AND bridge_status = 'processing'"
    ).bind(now, now, id).run();
  } else {
    result = await env.DB.prepare(
      "UPDATE orders SET bridge_status = 'error', bridge_error = ?, updated_at = ? WHERE id = ? AND bridge_status = 'processing'"
    ).bind(error || "UNKNOWN_ERROR", now, id).run();
  }

  if (!result.meta?.changes) return json({ ok: false, error: "NOT_PROCESSING" }, 409);
  return json({ ok: true, id, bridgeStatus: success ? "completed" : "error", device });
}

async function retry(env, id) {
  const now = new Date().toISOString();
  const result = await env.DB.prepare(
    "UPDATE orders SET bridge_status = 'pending', bridge_device = '', bridge_claimed_at = '', bridge_completed_at = '', bridge_error = '', updated_at = ? WHERE id = ? AND bridge_status = 'error'"
  ).bind(now, id).run();
  if (!result.meta?.changes) return json({ ok: false, error: "NOT_ERROR" }, 409);
  return json({ ok: true, id, bridgeStatus: "pending" });
}

async function status(env) {
  const since = currentBusinessDayStartIso();
  const result = await env.DB.prepare(
    "SELECT bridge_status, COUNT(*) AS count FROM orders WHERE created_at >= ? GROUP BY bridge_status"
  ).bind(since).all();

  const counts = {};
  for (const row of (result.results || [])) {
    counts[row.bridge_status || "legacy"] = Number(row.count || 0);
  }
  return json({
    ok: true,
    service: "ippuku-air-bridge-api",
    isolated: true,
    multiItemLearnedSupported: true,
    since,
    counts
  });
}

async function listMappings(env) {
  const result = await env.DB.prepare(
    "SELECT name, option_text, air_code, updated_at FROM air_product_map ORDER BY name ASC, option_text ASC"
  ).all();
  return json({
    ok: true,
    mappings: (result.results || []).map((x) => ({
      name: x.name,
      option: x.option_text || "",
      airCode: x.air_code,
      updatedAt: x.updated_at
    }))
  });
}

async function putMapping(request, env) {
  const body = await request.json().catch(() => null);
  const name = String(body?.name || "").trim().slice(0, 120);
  const optionText = String(body?.option || "").trim().slice(0, 240);
  const airCode = String(body?.airCode || "").trim().slice(0, 64);

  if (!name || !/^[0-9]+$/.test(airCode)) {
    return json({ ok: false, error: "INVALID_MAPPING" }, 400);
  }

  const now = new Date().toISOString();
  await env.DB.prepare(
    "INSERT INTO air_product_map (name, option_text, air_code, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(name, option_text) DO UPDATE SET air_code = excluded.air_code, updated_at = excluded.updated_at"
  ).bind(name, optionText, airCode, now).run();

  return json({ ok: true, name, option: optionText, airCode, updatedAt: now });
}

export default {
  async fetch(request, env) {
    if (!env.DB) return json({ ok: false, error: "D1_NOT_BOUND" }, 503);

    try {
      await ensureBridgeSchema(env);
    } catch (error) {
      return json({
        ok: false,
        error: "BRIDGE_SCHEMA_ERROR",
        detail: String(error?.message || error).slice(0, 300)
      }, 500);
    }

    const url = new URL(request.url);

    if ((url.pathname === "/" || url.pathname === "/health") && request.method === "GET") {
      return json({
        ok: true,
        service: "ippuku-air-bridge-api",
        isolated: true,
        database: true,
        multiItemLearnedSupported: true
      });
    }

    if (url.pathname === "/api/bridge/status" && request.method === "GET") return status(env);
    if (url.pathname === "/api/bridge/mappings" && request.method === "GET") return listMappings(env);
    if (url.pathname === "/api/bridge/mappings" && request.method === "PUT") return putMapping(request, env);
    if (url.pathname === "/api/bridge/claim" && request.method === "POST") return claim(request, env);

    const m = url.pathname.match(/^\/api\/bridge\/orders\/([^/]+)\/(complete|error|retry)$/);
    if (m) {
      const id = decodeURIComponent(m[1]);
      const action = m[2];
      if (action === "complete" && request.method === "POST") return finish(request, env, id, true);
      if (action === "error" && request.method === "POST") return finish(request, env, id, false);
      if (action === "retry" && request.method === "POST") return retry(env, id);
    }

    return json({ ok: false, error: "NOT_FOUND" }, 404);
  }
};
