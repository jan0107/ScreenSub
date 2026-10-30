#!/usr/bin/env python3
# Copyright (c) 2026 January
# Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

# -*- coding: utf-8 -*-
"""
阿里云账单用量探测（只读）。

目标：确认 BssOpenApi DescribeInstanceBill 能不能拿到
**机器翻译的字符用量**（含免费额度抵扣前的用量）。

关键字段：
  Usage                用量
  UsageUnit            用量单位（是不是"字符"？）
  DeductedByResourcePackage  资源包/免费额度抵扣量
  PretaxAmount         应付金额（免费额度内应为 0）

用法：
    python tools/aliyun_bill_probe.py <AccessKeyId> <AccessKeySecret> [BillingCycle]
"""

import base64
import datetime
import hashlib
import hmac
import json
import sys
import urllib.parse
import urllib.request
import uuid

ENDPOINT = "https://business.aliyuncs.com/"


def percent_encode(s: str) -> str:
    """阿里云 RPC 专用编码：空格→%20、*→%2A、~ 不编码"""
    out = urllib.parse.quote(str(s), safe="")
    return out.replace("+", "%20").replace("*", "%2A").replace("%7E", "~")


def call(ak: str, sk: str, params: dict) -> str:
    base = {
        "Format": "JSON",
        "AccessKeyId": ak,
        "SignatureMethod": "HMAC-SHA1",
        "SignatureVersion": "1.0",
        "SignatureNonce": uuid.uuid4().hex,
        "Timestamp": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "Version": "2017-12-14",
    }
    base.update(params)

    canonical = "&".join(
        f"{percent_encode(k)}={percent_encode(base[k])}" for k in sorted(base)
    )
    string_to_sign = "GET&%2F&" + percent_encode(canonical)
    digest = hmac.new(
        (sk + "&").encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1
    ).digest()
    base["Signature"] = base64.b64encode(digest).decode()

    query = "&".join(f"{percent_encode(k)}={percent_encode(base[k])}" for k in sorted(base))
    req = urllib.request.Request(f"{ENDPOINT}?{query}", method="GET")
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            return f"HTTP {resp.status}\n" + resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}\n" + e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return f"ERR {type(e).__name__}: {e}"


def main() -> None:
    if len(sys.argv) < 3:
        print(__doc__)
        return
    ak, sk = sys.argv[1].strip(), sys.argv[2].strip()
    cycle = sys.argv[3] if len(sys.argv) > 3 else datetime.date.today().strftime("%Y-%m")

    # 先不按产品过滤，把所有计费项都拉出来 —— 先看有没有权限、再找机器翻译那一行
    trials = [
        ("全量 + 计费项维度", {
            "Action": "DescribeInstanceBill",
            "BillingCycle": cycle,
            "IsBillingItem": "true",
            "MaxResults": "300",
        }),
        ("只看 alimt", {
            "Action": "DescribeInstanceBill",
            "BillingCycle": cycle,
            "IsBillingItem": "true",
            "ProductCode": "alimt",
            "MaxResults": "300",
        }),
        ("按日粒度", {
            "Action": "DescribeInstanceBill",
            "BillingCycle": cycle,
            "IsBillingItem": "true",
            "Granularity": "DAILY",
            "BillingDate": (datetime.date.today() - datetime.timedelta(days=1)).strftime("%Y-%m-%d"),
            "MaxResults": "300",
        }),
    ]

    for label, params in trials:
        print(f"\n===== {label} =====")
        text = call(ak, sk, params)
        print(text[:2500])

        if text.startswith("HTTP 200"):
            try:
                body = json.loads(text.split("\n", 1)[1])
                items = (body.get("Data") or {}).get("Items") or []
                print(f"---- 共 {len(items)} 条")
                for it in items[:12]:
                    print(
                        "  ", it.get("ProductCode"), "|", it.get("ProductDetail"),
                        "| Usage=", it.get("Usage"), it.get("UsageUnit"),
                        "| 金额=", it.get("PretaxAmount"),
                        "| 资源包抵扣=", it.get("DeductedByResourcePackage"),
                    )
            except Exception as e:  # noqa: BLE001
                print("  解析失败:", e)


if __name__ == "__main__":
    main()
