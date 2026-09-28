import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { createRelay } from "../src/server.mjs";

const activeRelays = [];

afterEach(async () => {
  await Promise.all(activeRelays.splice(0).map(({ server, dispose }) => {
    dispose();
    return new Promise((resolve) => server.close(resolve));
  }));
});

async function startRelay(options) {
  const relay = createRelay(options);
  relay.server.listen(0, "127.0.0.1");
  await new Promise((resolve) => relay.server.once("listening", resolve));
  activeRelays.push(relay);
  const { port } = relay.server.address();
  return `http://127.0.0.1:${port}`;
}

async function json(url, path, options = {}) {
  const response = await fetch(`${url}${path}`, options);
  return { response, body: response.status === 204 ? undefined : await response.json() };
}

async function createMailbox(url, ttlSeconds = 60) {
  const { response, body } = await json(url, "/v1/mailboxes", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ttlSeconds }),
  });
  assert.equal(response.status, 201);
  return body;
}

test("delivers opaque envelopes and deletes only with the retrieval capability", async () => {
  const url = await startRelay();
  const mailbox = await createMailbox(url);
  assert.match(mailbox.deliveryCapability, /^[A-Za-z0-9_-]{43}$/);
  assert.match(mailbox.retrievalCapability, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(mailbox.deliveryCapability, mailbox.retrievalCapability);

  const inserted = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${mailbox.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "AAECAw" }),
  });
  assert.equal(inserted.response.status, 201);

  const listed = await json(url, "/v1/envelopes", { headers: { authorization: `Bearer ${mailbox.retrievalCapability}` } });
  assert.deepEqual(listed.body.envelopes, [{ id: inserted.body.id, envelope: "AAECAw" }]);

  const wrongScope = await json(url, `/v1/envelopes/${inserted.body.id}`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${mailbox.deliveryCapability}` },
  });
  assert.equal(wrongScope.response.status, 404);

  const deleted = await json(url, `/v1/envelopes/${inserted.body.id}`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${mailbox.retrievalCapability}` },
  });
  assert.equal(deleted.response.status, 204);
});

test("rejects invalid capabilities, malformed envelopes, and oversized requests", async () => {
  const url = await startRelay({ maxEnvelopeBytes: 4, maxRequestBytes: 100 });
  const mailbox = await createMailbox(url);
  const unauthorized = await json(url, "/v1/envelopes");
  assert.equal(unauthorized.response.status, 401);

  const malformed = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${mailbox.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "not standard base64!" }),
  });
  assert.equal(malformed.response.status, 400);

  const oversizedEnvelope = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${mailbox.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: Buffer.alloc(5).toString("base64url") }),
  });
  assert.equal(oversizedEnvelope.response.status, 400);

  const oversizedRequest = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${mailbox.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "A".repeat(200) }),
  });
  assert.equal(oversizedRequest.response.status, 413);
});

test("enforces mailbox bounds and expires capabilities", async () => {
  const url = await startRelay({ maxMailboxItems: 1, maxMailboxBytes: 4, minTtlSeconds: 0, defaultTtlSeconds: 0, maxTtlSeconds: 60 });
  const mailbox = await createMailbox(url, 0);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const expired = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${mailbox.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "AA" }),
  });
  assert.equal(expired.response.status, 404);

  const liveMailbox = await createMailbox(url, 60);
  const first = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${liveMailbox.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "AA" }),
  });
  assert.equal(first.response.status, 201);
  const full = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${liveMailbox.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "AQ" }),
  });
  assert.equal(full.response.status, 507);
});

test("bounds global mailbox and envelope memory", async () => {
  const url = await startRelay({ maxMailboxes: 2, maxTotalEnvelopeBytes: 2 });
  const first = await createMailbox(url);
  const second = await createMailbox(url);
  const third = await json(url, "/v1/mailboxes", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ttlSeconds: 60 }),
  });
  assert.equal(third.response.status, 503);

  const one = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${first.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "AAE" }),
  });
  assert.equal(one.response.status, 201);
  const full = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${second.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "Ag" }),
  });
  assert.equal(full.response.status, 507);
  const deleted = await json(url, `/v1/envelopes/${one.body.id}`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${first.retrievalCapability}` },
  });
  assert.equal(deleted.response.status, 204);
  const afterDelete = await json(url, "/v1/envelopes", {
    method: "POST",
    headers: { authorization: `Bearer ${second.deliveryCapability}`, "content-type": "application/json" },
    body: JSON.stringify({ envelope: "Ag" }),
  });
  assert.equal(afterDelete.response.status, 201);
});
