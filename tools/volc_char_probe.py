#!/usr/bin/env python3
# Copyright (c) 2026 January
# Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

# -*- coding: utf-8 -*-
"""
火山引擎"字符用量"最终复验（只读，不改任何资源）。

## 今天要回答的问题（2026-10-29）

昨天的结论是"火山拿不到真实字符数"，但留了两个未验证的口子：
  1. 机器翻译实例 `Translate2000001042242810306` 是 10-27 11:11 才开通的，
     所以 9 月、乃至 10 月初账单全是 0 行是**正常的**，不能据此判死账单路。
  2. 分账账单（`ListSplitBillDetail`）昨天没查过；官方文档明确它有
     `Count`（账单明细用量）、`Unit`、`DeductionCount`（资源包抵扣量）三个字段——
     这正是"字符数"落库的地方，而且**免费额度抵扣也会留痕**（DeductionCount > 0）。

## 官方口径的两个坑（已查文档确认，昨天没注意到）

  - 分账账单比账单明细**再晚 2 天**，官方建议查 **T-5** 的日期。所以今天查 10-28
    大概率还是空，查 10-24 之前才有意义——但实例 10-27 才开通，那就只能等。
  - `ExpenseDate` 只在 `GroupPeriod=1|2` 时有效，且必须与账期同月。

## 用法

    # 1) 环境变量（推荐，不落盘）
    set VOLC_AK=AKLT...   &   set VOLC_SK=...
    python tools/volc_char_probe.py

    # 2) 命令行
    python tools/volc_char_probe.py <AK> <SK> [账期 YYYY-MM]

    # 3) 只跑某一组
    python tools/volc_char_probe.py --only split
"""

import datetime
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request

HOST = "open.volcengineapi.com"
SERVICE = "billing"
REGION = "cn-north-1"
VERSION = "2022-01-01"
SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"

# 用户账号里的机器翻译实例（控制台开通页截图里的 ID）
INSTANCE_NO = "Translate2000001042242810306"


# ---------------- 签名 ----------------

def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def _sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def sign(ak: str, sk: str, query: str, body: str) -> dict:
    now = datetime.datetime.now(datetime.timezone.utc)
    x_date = now.strftime("%Y%m%dT%H%M%SZ")
    short_date = x_date[:8]

    payload_hash = _sha256_hex(body)
    content_type = "application/json"
    canonical_headers = (
        f"content-type:{content_type}\n"
        f"host:{HOST}\n"
        f"x-content-sha256:{payload_hash}\n"
        f"x-date:{x_date}\n"
    )
    canonical_request = "\n".join(
        ["POST", "/", query, canonical_headers, SIGNED_HEADERS, payload_hash]
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

    return {
        "Authorization": (
            f"HMAC-SHA256 Credential={ak}/{scope}, "
            f"SignedHeaders={SIGNED_HEADERS}, Signature={signature}"
        ),
        "Content-Type": content_type,
        "X-Date": x_date,
        "X-Content-Sha256": payload_hash,
    }


def call(ak: str, sk: str, action: str, body: dict):
    query = f"Action={action}&Version={VERSION}"
    payload = json.dumps(body, separators=(",", ":"), ensure_ascii=False)
    headers = sign(ak, sk, query, payload)
    req = urllib.request.Request(
        f"https://{HOST}/?{query}",
        data=payload.encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, f"{type(e).__name__}: {e}"


# ---------------- 结果归纳 ----------------

def summarize(status: int, text: str) -> dict:
    out = {"http": status, "rows": 0, "total": -1, "error": "", "warning": "",
           "products": set(), "translate_rows": []}
    try:
        d = json.loads(text)
    except Exception:  # noqa: BLE001
        out["error"] = f"非 JSON: {text[:150]}"
        return out

    err = (d.get("ResponseMetadata") or {}).get("Error") or {}
    if err:
        out["error"] = f'{err.get("Code", "")}: {err.get("Message", "")}'.strip()
        return out

    result = d.get("Result") or {}
    lst = result.get("List") or []
    out["rows"] = len(lst)
    out["total"] = result.get("Total", -1)
    out["warning"] = d.get("Warning") or result.get("Warning") or ""

    for row in lst:
        prod = str(row.get("Product") or row.get("ProductZh") or "")
        out["products"].add(prod)
        if "translate" in prod.lower() or "翻译" in prod:
            out["translate_rows"].append(row)
    return out


def show_row(row: dict) -> str:
    keys = ["ExpenseDate", "ExpenseTime", "Product", "ProductZh", "InstanceNo",
            "Element", "ConfigName", "Count", "Unit", "DeductionCount",
            "SplitItemAmount", "OriginalBillAmount", "PayableAmount", "Project"]
    parts = []
    for k in keys:
        if k in row and str(row[k]) not in ("-", ""):
            parts.append(f"{k}={row[k]}")
    return "  ".join(parts)


def run_case(ak, sk, label, action, body, raw=False):
    print(f"\n--- {label}")
    print(f"    {action}  {json.dumps(body, ensure_ascii=False)}")
    status, text = call(ak, sk, action, body)
    s = summarize(status, text)
    if s["error"]:
        print(f"    ✗ HTTP {status}  {s['error']}")
        return s
    print(f"    行数={s['rows']}  Total={s['total']}"
          + (f"  Warning={s['warning']}" if s["warning"] else ""))
    if s["products"]:
        print(f"    产品：{', '.join(sorted(s['products']))}")
    if s["translate_rows"]:
        print(f"    ★ translate 行 {len(s['translate_rows'])} 条：")
        for row in s["translate_rows"][:6]:
            print("      " + show_row(row))
    elif s["rows"]:
        print("    （有账单行，但没有 translate）首行样例：")
        print("      " + show_row((json.loads(text).get("Result") or {}).get("List")[0]))
    if raw and not s["error"]:
        print("    RAW: " + text[:1500])
    return s


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    only = ""
    for a in sys.argv[1:]:
        if a.startswith("--only"):
            only = a.split("=", 1)[1].lower() if "=" in a else "all"

    ak = args[0] if len(args) >= 1 else os.environ.get("VOLC_AK", "")
    sk = args[1] if len(args) >= 2 else os.environ.get("VOLC_SK", "")
    if not ak or not sk:
        print("缺少 AK/SK：设置环境变量 VOLC_AK / VOLC_SK，或命令行传入。\n")
        print(__doc__)
        return
    ak, sk = ak.strip(), sk.strip()
    # 火山 Secret 是 base64，末尾 == 漏了会报 SignatureDoesNotMatch
    sk += "=" * ((-len(sk)) % 4)

    month = args[2] if len(args) >= 3 else datetime.date.today().strftime("%Y-%m")
    yy, mm = month.split("-")
    today = datetime.date.today()

    def day(n: int) -> str:
        d = datetime.date(int(yy), int(mm), 1) + datetime.timedelta(days=n - 1)
        return d.strftime("%Y-%m-%d")

    print("=" * 78)
    print(f"火山字符用量复验 · 账期 {month} · 今天 {today:%Y-%m-%d}")
    print(f"实例 {INSTANCE_NO}")
    print("=" * 78)

    results = []

    if only in ("", "all", "split"):
        base = {"BillPeriod": month, "Limit": 300, "Offset": 0,
                "IgnoreZero": 0, "NeedRecordNum": 1}
        cases = [
            ("① 分账账单 · 账期汇总（全产品）", dict(base, GroupPeriod=0)),
            ("② 分账账单 · 按天（全产品）", dict(base, GroupPeriod=1)),
            ("③ 分账账单 · 按天 · 只看 translate", dict(base, GroupPeriod=1, Product=["translate"])),
            ("④ 分账账单 · 按天 · 只看实例", dict(base, GroupPeriod=1, InstanceNo=INSTANCE_NO)),
            ("⑤ 分账账单 · 明细 · 最近 3 天", dict(base, GroupPeriod=2, ExpenseDate=(
                datetime.date.today() - datetime.timedelta(days=1)).strftime("%Y-%m-%d"))),
        ]
        for label, body in cases:
            results.append((label, run_case(ak, sk, label, "ListSplitBillDetail", body)))
            time.sleep(0.35)  # 官方限流 5QPS

    if only in ("", "all", "bill"):
        base = {"BillPeriod": month, "Limit": 300, "Offset": 0,
                "IgnoreZero": 0, "NeedRecordNum": 1}
        cases = [
            ("⑥ 账单明细 · 按天（全产品）", dict(base, GroupPeriod=1)),
            ("⑦ 账单明细 · 按天 · 只看 translate", dict(base, GroupPeriod=1, Product=["translate"])),
            ("⑧ 账单明细 · 按天 · 只看实例", dict(base, GroupPeriod=1, InstanceNo=INSTANCE_NO)),
        ]
        for label, body in cases:
            results.append((label, run_case(ak, sk, label, "ListBillDetail", body)))
            time.sleep(0.35)

    if only in ("", "all", "pkg"):
        for label, body in [
            ("⑨ 资源包列表（免费额度若是资源包，这里能看到剩余）",
             {"MaxResults": "100", "Offset": "0"}),
            ("⑩ 余额 / 欠费", {}),
        ]:
            action = "QueryBalanceAcct" if "余额" in label else "ListResourcePackages"
            results.append((label, run_case(ak, sk, label, action, body)))
            time.sleep(0.35)

    print("\n" + "=" * 78)
    print("结论速览")
    print("=" * 78)
    for label, s in results:
        if s["error"]:
            print(f"  {label:38s} ✗ {s['error'][:70]}")
        elif s["translate_rows"]:
            print(f"  {label:38s} ★ translate {len(s['translate_rows'])} 行 ← 有戏")
        else:
            print(f"  {label:38s} 行数 {s['rows']}（无 translate）")

    print("""
判定规则：
  · 出现 translate 行且 Count>0           → 账单路走通，App 直接读这个数
  · 出现 translate 行但 Count=0、DeductionCount>0 → 用量记在资源包抵扣里，读 DeductionCount
  · 全部 0 行                              → 分账账单再等 2 天（T+2 延迟），
                                             11-02 出账后做最终判定
""")


if __name__ == "__main__":
    main()
