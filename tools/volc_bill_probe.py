#!/usr/bin/env python3
# Copyright (c) 2026 January
# Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

# -*- coding: utf-8 -*-
"""
火山引擎账单用量探测（只读）。

目的：确认 ListBillDetail 能不能拿到**机器翻译的字符用量**。
如果能，用量页就能自动回填"这个月已经用了多少"（含本次安装之前的历史消耗）；
如果只能拿到金额，那"免费额度内用量"就永远查不到，只能靠用户手填校准。

用法：
    python tools/volc_bill_probe.py <AK> <SK>

只做 GET/POST 账单查询，不改任何资源。
"""

import base64
import datetime
import hashlib
import hmac
import json
import sys
import urllib.request

HOST = "open.volcengineapi.com"
SERVICE = "billing"
REGION = "cn-north-1"


def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def _sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def sign(ak: str, sk: str, query: str, body: str) -> dict:
    now = datetime.datetime.now(datetime.timezone.utc)
    x_date = now.strftime("%Y%m%dT%H%M%SZ")
    short_date = x_date[:8]

    payload_hash = _sha256_hex(body)
    # 参与签名的头必须和实际发出的一致；这里只签这三个，避免 charset 之类被框架改写
    content_type = "application/json"
    canonical_headers = (
        f"content-type:{content_type}\n"
        f"host:{HOST}\n"
        f"x-content-sha256:{payload_hash}\n"
        f"x-date:{x_date}\n"
    )
    signed_headers = "content-type;host;x-content-sha256;x-date"
    canonical_request = "\n".join(
        ["POST", "/", query, canonical_headers, signed_headers, payload_hash]
    )

    scope = f"{short_date}/{REGION}/{SERVICE}/request"
    string_to_sign = "\n".join(
        ["HMAC-SHA256", x_date, scope, _sha256_hex(canonical_request)]
    )

    k_date = _hmac(sk.encode("utf-8"), short_date)
    k_region = _hmac(k_date, REGION)
    k_service = _hmac(k_region, SERVICE)
    k_signing = _hmac(k_service, "request")
    signature = hmac.new(
        k_signing, string_to_sign.encode("utf-8"), hashlib.sha256
    ).hexdigest()

    authorization = (
        f"HMAC-SHA256 Credential={ak}/{scope}, "
        f"SignedHeaders={signed_headers}, Signature={signature}"
    )
    return {
        "Authorization": authorization,
        "Content-Type": content_type,
        "X-Date": x_date,
        "X-Content-Sha256": payload_hash,
    }


def call(ak: str, sk: str, action: str, body: dict) -> tuple:
    query = f"Action={action}&Version=2022-01-01"
    payload = json.dumps(body, separators=(",", ":"), ensure_ascii=False)
    headers = sign(ak, sk, query, payload)
    req = urllib.request.Request(
        f"https://{HOST}/?{query}",
        data=payload.encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, f"{type(e).__name__}: {e}"


def main() -> None:
    if len(sys.argv) < 3:
        print(__doc__)
        return
    ak, sk = sys.argv[1].strip(), sys.argv[2].strip()
    # 火山 Secret 是 base64，末尾 == 漏了会报 SignatureDoesNotMatch；这里自动补齐
    pad = (-len(sk)) % 4
    if pad:
        sk += "=" * pad

    # 传入第三个参数时只试这一种 body，方便逐字段试错
    if len(sys.argv) >= 4:
        body = json.loads(sys.argv[3])
        status, text = call(ak, sk, sys.argv[4] if len(sys.argv) > 4 else "ListBillDetail", body)
        print(f"HTTP {status}")
        print(text[:2000])
        return

    month = datetime.date.today().strftime("%Y-%m")
    yesterday = (datetime.date.today() - datetime.timedelta(days=1)).strftime("%Y-%m-%d")

    # 字段名不靠猜：先发空 body，服务端会告诉我们缺什么
    trials = [
        ("只给 BillPeriod", {"BillPeriod": month}),
        ("BillPeriod 数组", {"BillPeriod": [month]}),
        ("BillPeriod 月初", {"BillPeriod": f"{month}-01"}),
        ("GroupPeriod 数字", {"BillPeriod": month, "GroupPeriod": 2}),
        ("GroupPeriod Month + 分页", {"BillPeriod": month, "GroupPeriod": "Month", "Limit": 10, "Offset": 0}),
        ("ExpenseDate", {"BillPeriod": month, "ExpenseDate": yesterday}),
    ]

    for label, body in trials:
        status, text = call(ak, sk, "ListBillDetail", body)
        print(f"\n=== {label} → HTTP {status}")
        print(text[:1200])


if __name__ == "__main__":
    main()
