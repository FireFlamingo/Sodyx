import { createServer } from "node:http";
import { randomBytes, timingSafeEqual, createHash } from "node:crypto";
import { RelayPersistence } from "./persistence.mjs";

export const DEFAULTS = Object.freeze({
  maxEnvelopeBytes: 256 * 1024,
  maxMailboxBytes: 8 * 1024 * 1024,
  maxMailboxItems: 128,
  maxMailboxes: 1024,
  maxTotalEnvelopeBytes: 64 * 1024 * 1024,
  defaultTtlSeconds: 24 * 60 * 60,
  minTtlSeconds: 60,
  maxTtlSeconds: 7 * 24 * 60 * 60,
  maxRequestBytes: 512 * 1024,
});

const CAPABILITY_BYTES = 32;
const MESSAGE_ID_BYTES = 16;
const BASE64URL = /^[A-Za-z0-9_-]+$/;

function opaqueToken(byteLength) {
  return randomBytes(byteLength).toString("base64url");
}

function capabilityHash(value) { return createHash("sha256").update(value).digest("hex"); }

function isBase64Url(value) {
  return typeof value === "string" && value.length > 0 && BASE64URL.test(value);
}

function decodeEnvelope(value, maxBytes) {
  if (!isBase64Url(value)) return null;
  const decoded = Buffer.from(value, "base64url");
  if (decoded.length === 0 || decoded.length > maxBytes) return null;
  // Reject non-canonical encodings so clients have one representation per envelope.
  return decoded.toString("base64url") === value ? decoded : null;
}

function sendJson(response, status, body) {
  const encoded = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": encoded.length,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(encoded);
}

function sendEmpty(response, status) {
  response.writeHead(status, {
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end();
}

async function readJson(request, maxBytes) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > maxBytes) throw new Error("request_too_large");
    chunks.push(chunk);
  }
  if (size === 0) throw new Error("invalid_json");
  try {
    const value = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (value === null || Array.isArray(value) || typeof value !== "object") throw new Error("invalid_json");
    return value;
  } catch (error) {
    if (error.message === "request_too_large") throw error;
    throw new Error("invalid_json");
  }
}

function parseAuthorization(request) {
  const header = request.headers.authorization;
  if (typeof header !== "string") return null;
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(header);
  return match ? match[1] : null;
}

function equalToken(left, right) {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

/**
 * Creates an in-memory opaque-envelope relay. It deliberately has no user
 * accounts, contact identifiers, address book, message inspection, or logs.
 */
export function createRelay(options = {}) {
  const config = { ...DEFAULTS, ...options };
  const persistence = config.storagePath ? new RelayPersistence(config.storagePath) : null;
  const mailboxes = persistence?.load(config) ?? new Map();
  let totalEnvelopeBytes = [...mailboxes.values()].reduce((sum, box) => sum + box.totalBytes, 0);

  function purgeExpired(now = Date.now()) {
    for (const [deliveryCapability, mailbox] of mailboxes) {
      if (mailbox.expiresAt <= now) {
        persistence?.deleteMailbox(deliveryCapability);
        totalEnvelopeBytes -= mailbox.totalBytes;
        mailboxes.delete(deliveryCapability);
      }
    }
  }

  // Expiry is enforced even when the mailbox is not accessed again. The timer
  // is unreferenced so embedding this relay does not keep a test process alive.
  const expiryTimer = setInterval(
    purgeExpired,
    Math.min(60_000, Math.max(1_000, config.minTtlSeconds * 1000)),
  );
  expiryTimer.unref();

  function findMailbox(capability, scope) {
    const hash = capabilityHash(capability);
    for (const [deliveryCapability, mailbox] of mailboxes) {
      const candidate = scope === "deliver" ? deliveryCapability : mailbox.retrievalCapability;
      if (equalToken(candidate, hash)) return { deliveryCapability, mailbox };
    }
    return null;
  }

  async function handler(request, response) {
    const capability = parseAuthorization(request);

    try {
      purgeExpired();
      const url = new URL(request.url, "http://relay.invalid");
      if (request.method === "POST" && url.pathname === "/v1/mailboxes") {
        const body = await readJson(request, 1024);
        const ttlSeconds = body.ttlSeconds ?? config.defaultTtlSeconds;
        if (!Number.isInteger(ttlSeconds) || ttlSeconds < config.minTtlSeconds || ttlSeconds > config.maxTtlSeconds) {
          return sendJson(response, 400, { error: "invalid_request" });
        }
        if (mailboxes.size >= config.maxMailboxes) {
          return sendJson(response, 503, { error: "relay_full" });
        }
        const deliveryCapability = opaqueToken(CAPABILITY_BYTES);
        const retrievalCapability = opaqueToken(CAPABILITY_BYTES);
        const expiresAt = Date.now() + ttlSeconds * 1000;
        const deliveryHash = capabilityHash(deliveryCapability);
        const retrievalHash = capabilityHash(retrievalCapability);
        persistence?.create(deliveryHash, retrievalHash, expiresAt);
        mailboxes.set(deliveryHash, {
          retrievalCapability: retrievalHash,
          expiresAt,
          totalBytes: 0,
          envelopes: new Map(),
        });
        return sendJson(response, 201, { deliveryCapability, retrievalCapability, expiresAt });
      }

      if (request.method === "POST" && url.pathname === "/v1/envelopes") {
        if (!capability) return sendJson(response, 401, { error: "unauthorized" });
        const found = findMailbox(capability, "deliver");
        if (!found) return sendJson(response, 404, { error: "not_found" });
        const body = await readJson(request, config.maxRequestBytes);
        const envelope = decodeEnvelope(body.envelope, config.maxEnvelopeBytes);
        if (!envelope || Object.keys(body).some((key) => key !== "envelope")) {
          return sendJson(response, 400, { error: "invalid_request" });
        }
        const mailbox = found.mailbox;
        if (mailbox.expiresAt <= Date.now() || !mailboxes.has(found.deliveryCapability)) {
          return sendJson(response, 404, { error: "not_found" });
        }
        if (
          mailbox.envelopes.size >= config.maxMailboxItems ||
          mailbox.totalBytes + envelope.length > config.maxMailboxBytes ||
          totalEnvelopeBytes + envelope.length > config.maxTotalEnvelopeBytes
        ) {
          return sendJson(response, 507, { error: "mailbox_full" });
        }
        const id = opaqueToken(MESSAGE_ID_BYTES);
        const record = { envelope: envelope.toString("base64url"), byteLength: envelope.length };
        persistence?.add(found.deliveryCapability, id, record);
        mailbox.envelopes.set(id, record);
        mailbox.totalBytes += envelope.length;
        totalEnvelopeBytes += envelope.length;
        return sendJson(response, 201, { id });
      }

      if (request.method === "GET" && url.pathname === "/v1/envelopes") {
        if (!capability) return sendJson(response, 401, { error: "unauthorized" });
        const found = findMailbox(capability, "retrieve");
        if (!found) return sendJson(response, 404, { error: "not_found" });
        const envelopes = [...found.mailbox.envelopes].map(([id, record]) => ({ id, envelope: record.envelope }));
        return sendJson(response, 200, { envelopes });
      }

      if (request.method === "DELETE" && url.pathname === "/v1/mailboxes") {
        if (!capability) return sendJson(response, 401, { error: "unauthorized" });
        const found = findMailbox(capability, "retrieve");
        if (!found) return sendJson(response, 404, { error: "not_found" });
        persistence?.deleteMailbox(found.deliveryCapability);
        totalEnvelopeBytes -= found.mailbox.totalBytes;
        mailboxes.delete(found.deliveryCapability);
        return sendEmpty(response, 204);
      }

      const match = /^\/v1\/envelopes\/([A-Za-z0-9_-]{22})$/.exec(url.pathname);
      if (request.method === "DELETE" && match) {
        if (!capability) return sendJson(response, 401, { error: "unauthorized" });
        const found = findMailbox(capability, "retrieve");
        if (!found) return sendJson(response, 404, { error: "not_found" });
        const record = found.mailbox.envelopes.get(match[1]);
        if (!record) return sendJson(response, 404, { error: "not_found" });
        persistence?.deleteMessage(found.deliveryCapability, match[1]);
        found.mailbox.envelopes.delete(match[1]);
        found.mailbox.totalBytes -= record.byteLength;
        totalEnvelopeBytes -= record.byteLength;
        return sendEmpty(response, 204);
      }

      return sendJson(response, 404, { error: "not_found" });
    } catch (error) {
      if (error.message === "request_too_large") return sendJson(response, 413, { error: "request_too_large" });
      return sendJson(response, 400, { error: "invalid_request" });
    }
  }

  const server = createServer(handler);
  server.headersTimeout = 10_000;
  server.requestTimeout = 20_000;
  return {
    server,
    purgeExpired,
    dispose: () => { clearInterval(expiryTimer); persistence?.close(); },
  };
}
