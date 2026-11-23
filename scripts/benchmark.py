#!/usr/bin/env python3
"""Run concurrent taker orders against a seeded BTC-NGN book on a local Compose stack."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
import subprocess
import time
from urllib.request import Request, urlopen
from uuid import uuid4


def sql(query):
    return subprocess.check_output(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-X",
         "-v", "ON_ERROR_STOP=1", "-Atq", "-U", "liquidity", "-d", "order_service"],
        input=query, text=True,
    ).strip()


def token(user):
    project = os.environ.get("COMPOSE_PROJECT_NAME", "liquidity-engine")
    response = subprocess.check_output([
        "docker", "run", "--rm", "--network", f"{project}_default",
        "curlimages/curl:8.12.1", "-fsS", "--max-time", "10",
        "http://oauth:8080/liquidity-engine/token", "-d", "grant_type=client_credentials",
        "-d", f"client_id={user}", "-d", "client_secret=demo", "-d", "scope=liquidity-engine",
    ], text=True)
    return json.loads(response)["access_token"]


def place(jwt, side, lot):
    base = os.environ.get("ORDER_BASE_URL", "http://localhost:8081")
    order = {"market": "BTC-NGN", "side": side, "type": "LIMIT",
             "price": "50000000", "quantity": lot}
    request = Request(f"{base}/api/v1/orders", method="POST", data=json.dumps(order).encode(),
                      headers={"Content-Type": "application/json", "Authorization": f"Bearer {jwt}",
                               "Idempotency-Key": str(uuid4())})
    start = time.perf_counter()
    with urlopen(request, timeout=15) as response:
        result = json.load(response)
        if response.status != 201:
            raise RuntimeError(f"Expected order creation, got {response.status}")
    return result, (time.perf_counter() - start) * 1000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--orders", type=int, default=200)
    parser.add_argument("--workers", type=int, default=16)
    args = parser.parse_args()
    if not 1 <= args.orders <= 5000 or not 1 <= args.workers <= 64:
        parser.error("orders must be 1–5000 and workers must be 1–64")

    seller, buyer = uuid4(), uuid4()
    seller_jwt, buyer_jwt = token(seller), token(buyer)
    sql(f"""
        BEGIN;
        INSERT INTO wallet_opening_balances VALUES
            ('{seller}', 'BTC', {args.orders} * 0.0001), ('{buyer}', 'NGN', {args.orders} * 5000);
        INSERT INTO wallets (user_id, asset, balance)
            SELECT user_id, asset, amount FROM wallet_opening_balances WHERE user_id IN ('{seller}', '{buyer}');
        COMMIT;
    """)
    # One resting maker order per taker avoids measuring an empty book or order insertion alone.
    for _ in range(args.orders):
        order, _ = place(seller_jwt, "SELL", "0.0001")
        if order["status"] != "OPEN":
            raise RuntimeError("Benchmark requires a book with no crossing buy orders")

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda _: place(buyer_jwt, "BUY", "0.0001"), range(args.orders)))
    elapsed = time.perf_counter() - started

    if any(order["status"] != "FILLED" for order, _ in results):
        raise RuntimeError("Every measured order must be fully filled")
    verified = sql(f"""
        SELECT count(*) FROM trading_fills f
        JOIN orders b ON b.id = f.buy_order_id AND b.user_id = '{buyer}'
        JOIN orders s ON s.id = f.sell_order_id AND s.user_id = '{seller}'
        WHERE f.quantity = 0.0001 AND f.price = 50000000;
    """)
    fifo = sql(f"""
        SELECT array_agg(s.priority ORDER BY b.priority) = array_agg(s.priority ORDER BY s.priority)
        FROM trading_fills f JOIN orders b ON b.id = f.buy_order_id AND b.user_id = '{buyer}'
        JOIN orders s ON s.id = f.sell_order_id AND s.user_id = '{seller}';
    """)
    if verified != str(args.orders) or fifo != "t" or sql("SELECT count(*) FROM trading_discrepancies;") != "0":
        raise RuntimeError("Fill count, FIFO, or reconciliation failed")

    latencies = sorted(latency for _, latency in results)
    for percentile in (50, 95, 99):
        value = latencies[math.ceil(len(latencies) * percentile / 100) - 1]
        print(f"p{percentile} {value:.2f} ms")
    print(f"{args.orders / elapsed:.1f} orders/s, {args.orders} orders, {args.workers} workers, reconciliation passed")


if __name__ == "__main__":
    main()
