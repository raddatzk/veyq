#!/usr/bin/env python3
"""Throwaway benchmark for inbox #387: id column type VARCHAR(36) vs BINARY(16) vs MariaDB's native UUID
(and VARCHAR(36) in ascii_bin, the no-type-change variant).

Not a gate, not part of any check. It starts ONE disposable MariaDB container of the exact image the project pins
(read from infra/docker-compose.yml), publishes no port (it is driven with `docker exec`), uses its own container
and volume names (veyq-bench-387), and removes both when it finishes -- it touches nothing else of Docker's. Only the stdlib is used.

The shape is the hot one of the schema: `veyq_node` (random-UUIDv4 id PK, scope_id, parent_id, the unique
(parent_id, slug_token) index that serves "children of a parent", the (scope_id) FK index, the share_identity_id FK
index and the unique (parent_id, share_identity_id) index) and `veyq_node_attachment` (id PK, FK to the node,
unique (node_id, slot), unique blob_id, scope and share-identity indexes), with the same payload columns.

    python3 scripts/bench/id_key_bench.py --rows 1000000 --out /tmp/bench-1m.json
    python3 scripts/bench/id_key_bench.py --rows 10000000 --variants vc,bin --out /tmp/bench-10m.json

Reading the numbers: the container's buffer pool is small on purpose (128M) so locality matters. "cold" is the
first pass after a container restart (empty buffer pool, but the Docker VM's page cache stays warm -- so it is a
buffer-pool-cold number, not a disk-cold one). Timing is taken inside the server (stored procedures), so the
`docker exec` round trip is not in it, but a loaded host (another build running) still moves it: repeat and take
the median, which is what this script reports.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NAME = "veyq-bench-387"
VOLUME = "veyq-bench-387-data"
PASSWORD = "bench-only-387"
POOL = "128M"

VARIANTS = {
    "vc": {"type": "VARCHAR(36)", "conv": lambda c: c},
    "ascii": {"type": "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin", "conv": lambda c: c},
    "bin": {"type": "BINARY(16)", "conv": lambda c: f"UNHEX(REPLACE({c}, '-', ''))"},
    "uuid": {"type": "UUID", "conv": lambda c: c},
}


def sh(args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kw).stdout


def image() -> str:
    text = (ROOT / "infra" / "docker-compose.yml").read_text()
    m = re.search(r"image:\s*(mariadb:\S+@sha256:[0-9a-f]{64})", text)
    if not m:
        sys.exit("no pinned mariadb image in infra/docker-compose.yml")
    return m.group(1)


def sql(statement: str, db: str = "") -> str:
    """Run [statement] in the bench container; return stdout (tab separated, no header)."""
    return sh(["docker", "exec", "-i", NAME, "mariadb", "-uroot", f"-p{PASSWORD}", "-N", "-B", db],
              input=statement)


def wait_ready() -> None:
    for _ in range(120):
        try:
            sql("SELECT 1")
            return
        except subprocess.CalledProcessError:
            time.sleep(1)
    sys.exit("bench container did not come up")


def start(img: str) -> None:
    sh(["docker", "run", "-d", "--name", NAME, "-v", f"{VOLUME}:/var/lib/mysql",
        "-e", f"MARIADB_ROOT_PASSWORD={PASSWORD}", img,
        f"--innodb-buffer-pool-size={POOL}"])
    wait_ready()


def restart() -> None:
    sh(["docker", "restart", NAME])
    wait_ready()


def cleanup() -> None:
    subprocess.run(["docker", "rm", "-f", NAME], capture_output=True)
    subprocess.run(["docker", "volume", "rm", VOLUME], capture_output=True)


def stage(n: int, extra: int) -> None:
    """Shared staging data in db `stg`: canonical-UUID strings in insertion order, identical for every variant."""
    total = n + extra
    pnodes = max(n // 50, 100)
    scopes = max(n // 1000, 100)
    sql(f"""
DROP DATABASE IF EXISTS stg; CREATE DATABASE stg; USE stg;
DELIMITER //
CREATE FUNCTION u4() RETURNS CHAR(36) NOT DETERMINISTIC
BEGIN
  DECLARE h CHAR(32) DEFAULT LOWER(HEX(RANDOM_BYTES(16)));
  RETURN CONCAT(SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-4',SUBSTR(h,14,3),'-',
                ELT(1+FLOOR(RAND()*4),'8','9','a','b'),SUBSTR(h,18,3),'-',SUBSTR(h,21,12));
END//
DELIMITER ;
CREATE TABLE pp (k INT PRIMARY KEY, id CHAR(36) NOT NULL);
CREATE TABLE sc (k INT PRIMARY KEY, id CHAR(36) NOT NULL);
INSERT INTO pp SELECT seq, u4() FROM seq_1_to_{pnodes};
INSERT INTO sc SELECT seq, u4() FROM seq_1_to_{scopes};
CREATE TABLE stg_node (seq INT PRIMARY KEY, id CHAR(36) NOT NULL, parent_id CHAR(36) NOT NULL,
  scope_id CHAR(36) NOT NULL, slug VARCHAR(64) NOT NULL, name VARCHAR(80) NOT NULL, wck VARCHAR(120) NOT NULL);
""")
    chunk = 500_000
    for lo in range(1, total + 1, chunk):
        hi = min(lo + chunk - 1, total)
        sql(f"""USE stg;
INSERT INTO stg_node
SELECT s.seq, IF(s.seq <= {pnodes}, pp0.id, u4()), pp.id, sc.id,
       LEFT(REPLACE(REPLACE(TO_BASE64(RANDOM_BYTES(33)),'+','-'),'/','_'),43),
       REPLACE(TO_BASE64(RANDOM_BYTES(48)),CHAR(10),''),
       REPLACE(TO_BASE64(RANDOM_BYTES(72)),CHAR(10),'')
FROM seq_{lo}_to_{hi} s
LEFT JOIN pp pp0 ON pp0.k = s.seq
JOIN pp ON pp.k = 1 + ((s.seq * 2654435761) % {pnodes})
JOIN sc ON sc.k = 1 + ((s.seq * 40503) % {scopes});""")
    # one attachment (the cover) per fourth node
    sql(f"""USE stg;
CREATE TABLE stg_att (a INT PRIMARY KEY, id CHAR(36) NOT NULL, node_id CHAR(36) NOT NULL,
  scope_id CHAR(36) NOT NULL, blob_id CHAR(36) NOT NULL);
INSERT INTO stg_att SELECT s.seq, u4(), n.id, n.scope_id, u4()
  FROM seq_1_to_{total // 4} s JOIN stg_node n ON n.seq = s.seq * 4;
CREATE TABLE stg_probe (k INT PRIMARY KEY, id CHAR(36) NOT NULL);
INSERT INTO stg_probe SELECT s.seq, n.id FROM seq_1_to_200000 s
  JOIN stg_node n ON n.seq = 1 + ((s.seq * 2654435761) % {n});
CREATE TABLE stg_parprobe (k INT PRIMARY KEY, id CHAR(36) NOT NULL);
INSERT INTO stg_parprobe SELECT k, id FROM pp;""")


def ddl(v: dict) -> str:
    t = v["type"]
    return f"""
CREATE TABLE node (
  id {t} NOT NULL PRIMARY KEY, scope_id {t} NOT NULL, parent_id {t} NOT NULL, kind VARCHAR(16) NOT NULL,
  encrypted_name TEXT NOT NULL, slug_token VARCHAR(128) NOT NULL, order_index INT NOT NULL,
  wrapped_content_key TEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL, wrapped_meta TEXT NULL,
  epoch INT NOT NULL DEFAULT 0, deleted_at TIMESTAMP NULL, sealed_drop_private_key TEXT NULL,
  meta_version BIGINT NOT NULL DEFAULT 0, placement_version BIGINT NOT NULL DEFAULT 0,
  share_identity_id {t} NULL,
  UNIQUE KEY ux_node_parent_slug (parent_id, slug_token),
  KEY ix_node_scope (scope_id),
  KEY ix_node_share_identity (share_identity_id),
  UNIQUE KEY ux_node_parent_identity (parent_id, share_identity_id)
);
CREATE TABLE att (
  id {t} NOT NULL PRIMARY KEY, scope_id {t} NOT NULL, node_id {t} NOT NULL, slot VARCHAR(16) NOT NULL,
  blob_id {t} NOT NULL, share_identity_id {t} NULL,
  UNIQUE KEY ux_attachment_node_slot (node_id, slot),
  UNIQUE KEY ux_attachment_blob (blob_id),
  KEY ix_attachment_scope (scope_id),
  KEY ix_attachment_share_identity (share_identity_id),
  CONSTRAINT fk_attachment_node FOREIGN KEY (node_id) REFERENCES node (id)
);
CREATE TABLE probe (k INT PRIMARY KEY, id {t} NOT NULL);
CREATE TABLE parprobe (k INT PRIMARY KEY, id {t} NOT NULL);
DELIMITER //
CREATE PROCEDURE p_run(IN kind INT, IN off INT, IN n INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE t0 DATETIME(6) DEFAULT NOW(6);
  WHILE i < n DO
    IF kind = 0 THEN SELECT order_index INTO @x FROM node WHERE id = (SELECT id FROM probe WHERE k = off + i + 1);
    ELSEIF kind = 1 THEN SELECT SUM(order_index) INTO @x FROM node
         WHERE parent_id = (SELECT id FROM parprobe WHERE k = off + i + 1);
    ELSEIF kind = 2 THEN SELECT COUNT(a.slot) INTO @x FROM node n LEFT JOIN att a ON a.node_id = n.id
         WHERE n.parent_id = (SELECT id FROM parprobe WHERE k = off + i + 1);
    ELSEIF kind = 3 THEN SELECT id INTO @x FROM probe WHERE k = off + i + 1;
    ELSE SELECT id INTO @x FROM parprobe WHERE k = off + i + 1;
    END IF;
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, t0, NOW(6));
END//
CREATE PROCEDURE p_ins(IN s0 INT, IN batches INT, IN bsize INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE s INT;
  DECLARE t0 DATETIME(6) DEFAULT NOW(6);
  WHILE i < batches DO
    SET s = s0 + i * bsize;
    INSERT INTO node (id, scope_id, parent_id, kind, encrypted_name, slug_token, order_index,
                      wrapped_content_key, created_at)
      SELECT {v['conv']('id')}, {v['conv']('scope_id')}, {v['conv']('parent_id')}, 'FOLDER', name, slug, 0, wck,
             NOW(3) FROM stg.stg_node WHERE seq >= s AND seq < s + bsize;
    INSERT INTO att (id, scope_id, node_id, slot, blob_id)
      SELECT {v['conv']('id')}, {v['conv']('scope_id')}, {v['conv']('node_id')}, 'COVER', {v['conv']('blob_id')}
        FROM stg.stg_att WHERE a >= (s + 3) DIV 4 AND a <= (s + bsize - 1) DIV 4;
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, t0, NOW(6));
END//
DELIMITER ;
"""


def timed(db: str, call: str) -> float:
    """Microseconds a stored-procedure call reports for itself."""
    return int(sql(f"CALL {call};", db).split()[-1])


def load(db: str, v: dict, n: int) -> dict:
    sql(f"DROP DATABASE IF EXISTS {db}; CREATE DATABASE {db};")
    sql(ddl(v), db)
    c = v["conv"]
    t0 = time.time()
    chunk = 100_000
    for lo in range(1, n + 1, chunk):
        hi = min(lo + chunk, n + 1)
        sql(f"""INSERT INTO node (id, scope_id, parent_id, kind, encrypted_name, slug_token, order_index,
 wrapped_content_key, created_at)
 SELECT {c('id')}, {c('scope_id')}, {c('parent_id')}, 'FOLDER', name, slug, 0, wck, NOW(3)
 FROM stg.stg_node WHERE seq >= {lo} AND seq < {hi};""", db)
    t_node = time.time() - t0
    t0 = time.time()
    for lo in range(1, n // 4 + 1, chunk):
        hi = min(lo + chunk, n // 4 + 1)
        sql(f"""INSERT INTO att (id, scope_id, node_id, slot, blob_id)
 SELECT {c('id')}, {c('scope_id')}, {c('node_id')}, 'COVER', {c('blob_id')}
 FROM stg.stg_att WHERE a >= {lo} AND a < {hi};""", db)
    t_att = time.time() - t0
    sql(f"INSERT INTO probe SELECT k, {c('id')} FROM stg.stg_probe; "
        f"INSERT INTO parprobe SELECT k, {c('id')} FROM stg.stg_parprobe;", db)
    sql("ANALYZE TABLE node, att;", db)
    return {"load_node_s": t_node, "load_att_s": t_att,
            "load_rows_per_s": (n + n // 4) / (t_node + t_att)}


def sizes(db: str) -> dict:
    out = {}
    for line in sql(f"""SELECT table_name, data_length, index_length FROM information_schema.tables
 WHERE table_schema='{db}' AND table_name IN ('node','att');""").splitlines():
        t, d, i = line.split("\t")
        out[t] = {"data_mb": int(d) / 2**20, "index_mb": int(i) / 2**20}
    idx = {}
    for line in sql(f"""SELECT table_name, index_name, stat_value*@@innodb_page_size FROM mysql.innodb_index_stats
 WHERE database_name='{db}' AND table_name IN ('node','att') AND stat_name='size';""").splitlines():
        t, i, b = line.split("\t")
        idx[f"{t}.{i}"] = int(float(b)) / 2**20
    out["indexes_mb"] = idx
    return out


def med(xs):
    return statistics.median(xs)


def reads(db: str, n: int, warm_passes: int = 3) -> dict:
    """Raw microseconds per iteration (procedure-loop overhead and the probe-table read included; probe_only_us is
    that overhead alone, indicative -- the same loop reading just the probe table)."""
    res = {}
    cnt = {0: 20_000, 1: 1_000, 2: 1_000}
    names = {0: "point", 1: "children", 2: "children_join_att"}
    over = {0: 3, 1: 4, 2: 4}
    for kind, name in names.items():
        k_n = cnt[kind]
        passes = []
        for p in range(warm_passes):
            passes.append(timed(db, f"p_run({kind}, {(p + 1) * k_n}, {k_n})") / k_n)
        hot = [timed(db, f"p_run({kind}, 0, {k_n})") for _ in range(2)]
        base = timed(db, f"p_run({over[kind]}, 0, {k_n})") / k_n
        res[name] = {"steady_us": med(passes), "steady_passes_us": passes,
                     "hot_us": hot[-1] / k_n, "probe_only_us": base}
    return res


def cold_reads(db: str, reps: int) -> dict:
    out = {"point": [], "children": [], "children_join_att": []}
    cnt = {0: 20_000, 1: 1_000, 2: 1_000}
    names = {0: "point", 1: "children", 2: "children_join_att"}
    for r in range(reps):
        restart()
        for kind, name in names.items():
            k_n = cnt[kind]
            # after a restart even the probe tables are cold: read them first, so the number is the node/att cost
            timed(db, f"p_run({3 if kind == 0 else 4}, {(r + 7) * k_n}, {k_n})")
            run = timed(db, f"p_run({kind}, {(r + 7) * k_n}, {k_n})")
            out[name].append(run / k_n)
    return {k: {"cold_us": med(v), "cold_passes_us": v} for k, v in out.items()}


def steady_insert(db: str, n: int, extra: int, reps_label: str) -> dict:
    bsize = 1000
    batches = extra // bsize
    us = timed(db, f"p_ins({n + 1}, {batches}, {bsize})")
    return {"insert_rows_per_s": (extra + extra // 4) / (us / 1e6)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, required=True, help="node rows (attachments = rows/4)")
    ap.add_argument("--variants", default="vc,bin,uuid")
    ap.add_argument("--cold-reps", type=int, default=3)
    ap.add_argument("--extra", type=int, default=100_000, help="rows for the steady-state insert phase")
    ap.add_argument("--fits", action="store_true", help="also run reads with a 2G pool (everything fits)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    cleanup()
    results = {"rows": a.rows, "pool": POOL, "image": image(), "variants": {}}
    try:
        start(image())
        results["version"] = sql("SELECT VERSION(), @@character_set_server, @@collation_server").strip()
        t0 = time.time()
        stage(a.rows, a.extra)
        results["stage_s"] = time.time() - t0
        for name in a.variants.split(","):
            v = VARIANTS[name]
            db = f"b_{name}"
            r = {}
            r.update(load(db, v, a.rows))
            r["sizes"] = sizes(db)
            if a.fits:
                sql("SET GLOBAL innodb_buffer_pool_size = 2147483648;")
                time.sleep(5)
                reads(db, a.rows, 1)  # warm everything into the big pool
                r["fits_pool"] = reads(db, a.rows)
                sql("SET GLOBAL innodb_buffer_pool_size = 134217728;")
                time.sleep(5)
            r["small_pool"] = reads(db, a.rows)
            r.update(cold_reads(db, a.cold_reps))
            r["steady_insert"] = steady_insert(db, a.rows, a.extra, name)
            r["sizes_after_insert"] = sizes(db)
            results["variants"][name] = r
            Path(a.out).write_text(json.dumps(results, indent=1))
            sql(f"DROP DATABASE {db};")
    finally:
        Path(a.out).write_text(json.dumps(results, indent=1))
        cleanup()
    print(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
