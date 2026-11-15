#!/usr/bin/env python3
"""调模型的最小客户端(OpenAI 兼容接口,标准库):打分、起草题目用。

地址和 key 从环境变量读:OPENAI_BASE_URL、OPENAI_API_KEY、OPENAI_EXTRA_BODY(厂商参数的 JSON,比如关闭思考)、
OPENAI_MAX_TOKENS_PARAM。调用方先用 read_env_file 把 .env 按字面读进环境(不要 source:值里有引号和花括号)。
"""
import json
import os
import re
import time
import urllib.error
import urllib.request


def read_env_file(path):
    """按字面解析 KEY=VALUE(docker compose 的 .env 语义),不做 shell 展开。"""
    env = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def http_json(url, body=None, headers=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    h = {"Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(url, data=data, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def parse_json_obj(text):
    """模型偶尔包 ```json 围栏或前后带话,取第一个 {...}。"""
    m = re.search(r"\{.*\}", text or "", re.S)
    if not m:
        raise ValueError("no JSON object in: %r" % (text or "")[:200])
    return json.loads(m.group(0))


class Client:
    def __init__(self, model, extra_body=None):
        self.model = model
        self.base = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
        self.key = os.environ.get("OPENAI_API_KEY", "")
        # 打分的模型可能和被测模型要求不同的厂商参数,可单独指定
        extra = (extra_body if extra_body is not None else os.environ.get("OPENAI_EXTRA_BODY", "")).strip()
        self.extra = json.loads(extra) if extra else {}
        self.param = os.environ.get("OPENAI_MAX_TOKENS_PARAM", "max_tokens")
        if not self.key:
            raise SystemExit("需要 OPENAI_API_KEY(写在 .env 里)")

    def ask(self, system, user, max_tokens=400):
        body = {"model": self.model, self.param: max_tokens,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}], **self.extra}
        for attempt in range(4):
            try:
                r = http_json(self.base + "/chat/completions", body,
                              {"Authorization": "Bearer " + self.key}, timeout=90)
                return parse_json_obj(r["choices"][0]["message"]["content"])
            except urllib.error.HTTPError as e:
                if e.code not in (429, 500, 502, 503, 529) or attempt == 3:
                    raise RuntimeError("model HTTP %d" % e.code) from None   # 不回显响应体
                time.sleep(2 ** attempt * 3)
            except (ValueError, KeyError):
                if attempt == 3:
                    raise
        raise RuntimeError("unreachable")
