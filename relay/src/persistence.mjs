import { DatabaseSync } from "node:sqlite";
import { mkdirSync, chmodSync } from "node:fs";
import { dirname, resolve } from "node:path";

/** Persists opaque envelopes and capability hashes, never bearer capabilities or plaintext. */
export class RelayPersistence {
  constructor(path) {
    const absolute = resolve(path);
    mkdirSync(dirname(absolute), { recursive: true, mode: 0o700 });
    this.db = new DatabaseSync(absolute);
    if (process.platform !== "win32") chmodSync(absolute, 0o600);
    this.db.exec(`
      PRAGMA foreign_keys=ON;
      PRAGMA journal_mode=DELETE;
      PRAGMA synchronous=FULL;
      PRAGMA secure_delete=ON;
      CREATE TABLE IF NOT EXISTS mailboxes (
        delivery_hash TEXT PRIMARY KEY, retrieval_hash TEXT UNIQUE NOT NULL, expires_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS envelopes (
        sequence INTEGER PRIMARY KEY AUTOINCREMENT, mailbox_hash TEXT NOT NULL
          REFERENCES mailboxes(delivery_hash) ON DELETE CASCADE,
        id TEXT UNIQUE NOT NULL, body TEXT NOT NULL, byte_length INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS envelopes_mailbox ON envelopes(mailbox_hash, sequence);
    `);
  }

  load(config) {
    this.db.prepare("DELETE FROM mailboxes WHERE expires_at <= ?").run(Date.now());
    const count = this.db.prepare("SELECT COUNT(*) AS count FROM mailboxes").get().count;
    const bytes = this.db.prepare("SELECT COALESCE(SUM(byte_length),0) AS bytes FROM envelopes").get().bytes;
    if (count > config.maxMailboxes || bytes > config.maxTotalEnvelopeBytes) {
      throw new Error("Stored relay state exceeds configured bounds");
    }
    const boxes = new Map();
    for (const row of this.db.prepare("SELECT * FROM mailboxes").all()) {
      const records = this.db.prepare(
        "SELECT id, body, byte_length FROM envelopes WHERE mailbox_hash=? ORDER BY sequence"
      ).all(row.delivery_hash);
      const totalBytes = records.reduce((sum, record) => sum + record.byte_length, 0);
      if (records.length > config.maxMailboxItems || totalBytes > config.maxMailboxBytes) {
        throw new Error("Stored mailbox exceeds configured bounds");
      }
      boxes.set(row.delivery_hash, {
        retrievalCapability: row.retrieval_hash,
        expiresAt: row.expires_at,
        totalBytes,
        envelopes: new Map(records.map((record) => [record.id, {
          envelope: record.body, byteLength: record.byte_length,
        }])),
      });
    }
    return boxes;
  }

  create(deliveryHash, retrievalHash, expiresAt) {
    this.db.prepare("INSERT INTO mailboxes VALUES(?,?,?)").run(deliveryHash, retrievalHash, expiresAt);
  }

  add(deliveryHash, id, record) {
    this.db.prepare(
      "INSERT INTO envelopes(mailbox_hash,id,body,byte_length) VALUES(?,?,?,?)"
    ).run(deliveryHash, id, record.envelope, record.byteLength);
  }

  deleteMessage(deliveryHash, id) {
    this.db.prepare("DELETE FROM envelopes WHERE mailbox_hash=? AND id=?").run(deliveryHash, id);
  }

  deleteMailbox(deliveryHash) {
    this.db.prepare("DELETE FROM mailboxes WHERE delivery_hash=?").run(deliveryHash);
  }

  close() { this.db.close(); }
}
