#!/usr/bin/env python3
# Copyright (c) 2026 January
# Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

# -*- coding: utf-8 -*-
"""
火山引擎机器翻译 密钥自检工具。

用法：
    python volc_check.py <AccessKeyId> <SecretAccessKey>

它会做三件事：
  1. 报告两个 Key 的长度和格式（对照火山的标准长度）
  2. 用官方 SignerV4 同款算法签名，真实调用一次 TranslateText
  3. 把火山返回的原始错误翻译成人话

为什么需要它：App 里点「测试翻译」只能看到一句错误，
这个脚本能直接告诉你"是 Key 长度不对"还是"是权限没开"。
"""
import sys
import json
import hashlib
import hmac
import datetime
import urllib.request
import urllib.error

HOST = "translate.volcengineapi.com"
REGION = "cn-north-1"
SERVICE = "translate"
QUERY = "Action=TranslateText&Version=2020-06-01"
CONTENT_TYPE = "application/json"


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def sign_v4(sk, payload_hash, x_date):
    """官方 SignerV4 的四步派生，与 SDK 逐字节一致"""
    short_date = x_date[:8]
    canonical_headers = (
        f"content-type:{CONTENT_TYPE}\n"
        f"host:{HOST}\n"
        f"x-content-sha256:{payload_hash}\n"
        f"x-date:{x_date}\n"
    )
    canonical_request = "\n".join([
        "POST", "/", QUERY, canonical_headers,
        "content-type;host;x-content-sha256;x-date", payload_hash,
    ])
    scope = f"{short_date}/{REGION}/{SERVICE}/request"
    string_to_sign = "\n".join([
        "HMAC-SHA256", x_date, scope,
        hashlib.sha256(canonical_request.encode()).hexdigest(),
    ])

    key = sk.encode()
    for part in (short_date, REGION, SERVICE, "request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    return hmac.new(key, string_to_sign.encode(), hashlib.sha256).hexdigest(), scope


def check_format(ak, sk):
    print("─" * 56)
    print("① Key 格式检查")
    print(f"  AccessKeyId     长度 {len(ak):3d}  应以 AKLT 开头：{ak.startswith('AKLT')}")
    print(f"  SecretAccessKey 长度 {len(sk):3d}  （base64，长度必须是 4 的倍数）")
    if len(sk) % 4 != 0:
        print(f"  ⚠ 长度不是 4 的倍数，缺 {4 - len(sk) % 4} 个 '=' 填充")
        print("    这是最常见的坑：控制台表格里双击选中会漏掉末尾的 ==，")
        print("    报错却是 SignatureDoesNotMatch，看起来像密钥无效。")
        print(f"    补上后应该是：{sk + '=' * (4 - len(sk) % 4)}")
    if " " in ak or " " in sk:
        print("  ⚠ 含空格，粘贴时可能带进了空白字符")
    if ak != ak.strip() or sk != sk.strip():
        print("  ⚠ 首尾有空白字符，会导致签名失败")


def call(ak, sk):
    payload = json.dumps(
        {"SourceLanguage": "en", "TargetLanguage": "zh",
         "TextList": ["You have got to be kidding me."]},
        ensure_ascii=False, separators=(",", ":"),
    )
    payload_hash = hashlib.sha256(payload.encode()).hexdigest()
    x_date = utc_now()
    signature, scope = sign_v4(sk, payload_hash, x_date)

    auth = (f"HMAC-SHA256 Credential={ak}/{scope}, "
            f"SignedHeaders=content-type;host;x-content-sha256;x-date, "
            f"Signature={signature}")

    req = urllib.request.Request(
        f"https://{HOST}/?{QUERY}",
        data=payload.encode(),
        method="POST",
        headers={
            "Authorization": auth,
            "Content-Type": CONTENT_TYPE,
            "X-Date": x_date,
            "X-Content-Sha256": payload_hash,
        },
    )

    print("─" * 56)
    print("② 真实调用 TranslateText")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode()
            print(f"  HTTP {r.status}")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"  HTTP {e.code}")

    try:
        j = json.loads(body)
    except Exception:
        print(f"  无法解析：{body[:200]}")
        return

    meta = j.get("ResponseMetadata", {})
    err = meta.get("Error")

    print("─" * 56)
    print("③ 结论")
    if err:
        code = err.get("Code", "")
        msg = err.get("Message", "")
        print(f"  错误码：{code}")
        print(f"  信息  ：{msg}")
        print()
        if code == "SignatureDoesNotMatch":
            print("  → AccessKeyId 有效（服务端认得），但 SecretAccessKey 不对。")
            print("    几乎一定是 Secret 复制不完整，去控制台点👁图标重新复制整段。")
        elif code == "InvalidAccessKey":
            print("  → AccessKeyId 本身不存在。检查是否填错，或密钥已被禁用/删除。")
        elif "NotOpen" in code:
            print("  → 密钥没问题，但账号没开通「机器翻译」。去控制台点「立即开通」。")
        elif code == "InvalidAuthorization":
            print("  → 请求头格式有问题。")
        else:
            print("  → 对照火山错误码文档处理。")
        return

    items = j.get("TranslationList") or []
    if items:
        print("  ✅ 成功了！")
        print(f"  译文：{items[0].get('Translation')}")
        if items[0].get("DetectedSourceLanguage"):
            print(f"  自动检测源语言：{items[0]['DetectedSourceLanguage']}")
    else:
        print(f"  返回里没有译文，原始响应：{body[:300]}")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    ak, sk = sys.argv[1].strip(), sys.argv[2].strip()
    check_format(ak, sk)
    call(ak, sk)


if __name__ == "__main__":
    main()
