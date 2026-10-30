#!/usr/bin/env python3
# Copyright (c) 2026 January
# Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

# -*- coding: utf-8 -*-
"""
火山引擎"用量查询"接口彻底探测（只读，不改任何资源）。

背景：阿里云找到了 `alimt` 的 GetTranslateReport（返回每小时字符数）。
火山这边之前只试过 translate 服务下 5 个 action 名就下了结论，不够彻底。

本脚本扫三个方向：
  A. translate 服务下的"用量/统计"类 Action（含阿里云同名的 GetTranslateReport）
  B. 云监控（Volc_Observe）的指标接口 —— 若机器翻译上报了字符指标，这里能查到
  C. billing 服务下的资源包/优惠/余额类 Action

判定依据是**错误码的差异**：
  InvalidActionOrVersion  → 该 action 在这个 service+version 下不存在
  其他任何错误（缺参数 / 权限 / 参数非法）→ **action 存在**，这就是突破点

用法：
    python tools/volc_usage_probe.py <AK> <SK> [A|B|C]
"""

import base64
import datetime
import hashlib
import hmac
import json
import sys
import urllib.error
import urllib.request

REGION = "cn-north-1"


def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def _sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def sign(ak: str, sk: str, host: str, service: str, query: str, body: str) -> dict:
    now = datetime.datetime.now(datetime.timezone.utc)
    x_date = now.strftime("%Y%m%dT%H%M%SZ")
    short_date = x_date[:8]

    payload_hash = _sha256_hex(body)
    content_type = "application/json"
    canonical_headers = (
        f"content-type:{content_type}\n"
        f"host:{host}\n"
        f"x-content-sha256:{payload_hash}\n"
        f"x-date:{x_date}\n"
    )
    signed_headers = "content-type;host;x-content-sha256;x-date"
    canonical_request = "\n".join(
        ["POST", "/", query, canonical_headers, signed_headers, payload_hash]
    )

    scope = f"{short_date}/{REGION}/{service}/request"
    string_to_sign = "\n".join(
        ["HMAC-SHA256", x_date, scope, _sha256_hex(canonical_request)]
    )

    k_date = _hmac(sk.encode("utf-8"), short_date)
    k_region = _hmac(k_date, REGION)
    k_service = _hmac(k_region, service)
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


def call(ak, sk, host, service, version, action, body=None):
    query = f"Action={action}&Version={version}"
    payload = json.dumps(body or {}, separators=(",", ":"), ensure_ascii=False)
    headers = sign(ak, sk, host, service, query, payload)
    req = urllib.request.Request(
        f"https://{host}/?{query}",
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


def verdict(status: int, text: str) -> str:
    """把响应归纳成一句话，关键是把'action 不存在'和'存在但缺参数'分开"""
    try:
        d = json.loads(text)
        err = (d.get("ResponseMetadata") or {}).get("Error") or {}
        code = err.get("Code", "")
        msg = err.get("Message", "")
    except Exception:  # noqa: BLE001
        return f"非 JSON / HTTP {status}: {text[:120]}"

    if code == "InvalidActionOrVersion":
        return "✗ action 不存在"
    if code:
        return f"★ 存在！Code={code} Msg={msg[:110]}"
    return f"✓ 成功 HTTP {status}: {text[:200]}"


def scan(ak, sk, title, host, service, versions, actions, body=None):
    print(f"\n{'=' * 70}\n{title}  (host={host} service={service})\n{'=' * 70}")
    hits = []
    for version in versions:
        alive = []
        for action in actions:
            st, tx = call(ak, sk, host, service, version, action, body)
            v = verdict(st, tx)
            if not v.startswith("✗"):
                alive.append((action, v))
                print(f"  [{version}] {action:28s} {v}")
        if not alive:
            print(f"  [{version}] 全部 action 都不存在")
        else:
            hits.extend((version, a, v) for a, v in alive)
    return hits


def main() -> None:
    if len(sys.argv) < 3:
        print(__doc__)
        return
    ak, sk = sys.argv[1].strip(), sys.argv[2].strip()
    pad = (-len(sk)) % 4
    if pad:
        sk += "=" * pad
    which = (sys.argv[3] if len(sys.argv) > 3 else "ABC").upper()

    month = datetime.date.today().strftime("%Y-%m")
    today = datetime.date.today().strftime("%Y-%m-%d")

    usage_actions = [
        # 阿里云同名接口，火山会不会也叫这个
        "GetTranslateReport",
        # 常见命名
        "GetUsage", "QueryUsage", "DescribeUsage", "ListUsage",
        "GetTranslateUsage", "QueryTranslateUsage", "DescribeTranslateUsage",
        "GetUsageReport", "QueryUsageReport",
        # 统计类
        "GetStat", "GetStats", "GetStatistics", "QueryStatistics",
        "DescribeStatistics", "GetTranslateStat", "QueryTranslateStat",
        "GetTranslateStatistics",
        "QueryStat", "ListStat", "GetReport", "QueryReport",
        # 额度/配额类
        "GetQuota", "QueryQuota", "DescribeQuota", "GetTranslateQuota",
    ]

    if "A" in which:
        scan(
            ak, sk, "A. translate 服务下的用量/统计类 Action",
            "translate.volcengineapi.com", "translate",
            ["2020-06-01", "2021-01-01"],
            usage_actions,
            {"BillPeriod": month},
        )

    if "B" in which:
        for ver in ["2021-06-03", "2020-12-18", "2022-01-01"]:
            scan(
                ak, sk, "B. 云监控指标接口",
                "open.volcengineapi.com", "Volc_Observe",
                [ver],
                ["ListMetrics", "GetMetricData", "ListNamespaces",
                 "ListProjects", "DescribeMetricList", "ListMetricData"],
                {"Namespace": "translate"},
            )

    if "C" in which:
        scan(
            ak, sk, "C. billing 服务下的资源包/优惠/余额",
            "open.volcengineapi.com", "billing",
            ["2022-01-01"],
            ["QueryBalanceAcct", "ListResourcePackages", "ListCoupon",
             "ListBillOverviewByProd", "ListBillDetail",
             "ListAmortizedCostBillDetail", "ListAmortizedCostBillMonthly"],
            {"BillPeriod": month, "Limit": 10, "Offset": 0},
        )

    print(f"\n（今天 {today}，账单月 {month}）")


if __name__ == "__main__":
    main()
