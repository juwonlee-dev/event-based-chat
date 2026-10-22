import base64, hashlib, hmac, json, os, sys, time, urllib.error, urllib.request

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
def jwt_secret():
    """만료 토큰을 직접 만들어 보려고 서명 키가 필요하다. JWT_SECRET 환경 변수, 없으면 저장소 루트의 .env에서 읽는다."""
    if os.environ.get("JWT_SECRET"):
        return os.environ["JWT_SECRET"]
    env = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".env")
    with open(env, encoding="utf-8") as f:
        for line in f:
            if line.startswith("JWT_SECRET="):
                return line.split("=", 1)[1].strip()
    sys.exit("JWT_SECRET을 찾을 수 없습니다(.env 또는 환경 변수).")


SECRET = jwt_secret()
results = []


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, dict(r.headers), (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, dict(e.headers), (json.loads(raw) if raw else None)


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("  " + str(detail) if detail else ""))


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


# 1. 로그인 성공 + /users/me
s, h, b = call("POST", "/auth/login", {"username": "insurance.user", "password": "chat1234!"})
check("login ok", s == 200 and b["tokenType"] == "Bearer" and b["accessToken"] and b["refreshToken"], s)
access, refresh = b["accessToken"], b["refreshToken"]
s, h, b = call("GET", "/users/me", token=access)
check("users/me", s == 200 and b["username"] == "insurance.user" and b["role"] == "USER"
      and b["category"] == "INSURANCE" and b["maxMessageLength"] == 2000, b)

# 2. 잘못된 비밀번호 / 없는 사용자
s, h, b = call("POST", "/auth/login", {"username": "bank.user", "password": "wrong"})
check("wrong password 401 problem", s == 401 and b["code"] == "AUTH_INVALID"
      and h.get("Content-Type", "").startswith("application/problem+json"), b)
s, h, b = call("POST", "/auth/login", {"username": "nobody", "password": "x"})
check("unknown user same 401", s == 401 and b["code"] == "AUTH_INVALID", b)

# 3. 토큰 없음 / 위조 / 만료
s, h, b = call("GET", "/users/me")
check("no token 401", s == 401 and b["code"] == "AUTH_INVALID" and h.get("WWW-Authenticate") == "Bearer", b)
s, h, b = call("GET", "/users/me", token=access[:-3] + "abc")
check("tampered token 401", s == 401 and b["code"] == "AUTH_INVALID", b)
payload = json.loads(base64.urlsafe_b64decode(access.split(".")[1] + "=="))
payload["exp"] = int(time.time()) - 300
payload["iat"] = int(time.time()) - 600
head = b64(json.dumps({"alg": "HS256"}).encode())
body = b64(json.dumps(payload).encode())
sig = b64(hmac.new(SECRET.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest())
s, h, b = call("GET", "/users/me", token=f"{head}.{body}.{sig}")
check("expired token AUTH_TOKEN_EXPIRED", s == 401 and b["code"] == "AUTH_TOKEN_EXPIRED", b)

# 4. 검증 오류
s, h, b = call("POST", "/auth/login", {"username": "", "password": ""})
check("validation 400", s == 400 and b["code"] == "VALIDATION_FAILED" and len(b["errors"]) == 2, b)

# 5. 갱신 회전 + 재사용 감지
s, h, b = call("POST", "/auth/refresh", {"refreshToken": refresh})
check("refresh rotates", s == 200 and b["refreshToken"] != refresh, s)
refresh2 = b["refreshToken"]
s, h, b = call("POST", "/auth/refresh", {"refreshToken": refresh})
check("old refresh reused -> REFRESH_TOKEN_REUSED", s == 401 and b["code"] == "REFRESH_TOKEN_REUSED", b)
s, h, b = call("POST", "/auth/refresh", {"refreshToken": refresh2})
check("family revoked after reuse", s == 401 and b["code"] == "REFRESH_TOKEN_INVALID", b)
s, h, b = call("POST", "/auth/refresh", {"refreshToken": "garbage"})
check("garbage refresh invalid", s == 401 and b["code"] == "REFRESH_TOKEN_INVALID", b)

# 6. 로그아웃(전체)
s, h, b = call("POST", "/auth/login", {"username": "bank.customer", "password": "chat1234!"})
a2, r2 = b["accessToken"], b["refreshToken"]
s, h, b = call("POST", "/auth/logout", {"all": True}, token=a2)
check("logout all 204", s == 204, s)
s, h, b = call("POST", "/auth/refresh", {"refreshToken": r2})
check("refresh after logout invalid", s == 401 and b["code"] == "REFRESH_TOKEN_INVALID", b)

# 7. 관리자 토큰의 role 클레임
s, h, b = call("POST", "/auth/login", {"username": "admin", "password": "chat1234!"})
s2, h2, me = call("GET", "/users/me", token=b["accessToken"])
check("admin me", s2 == 200 and me["role"] == "ADMIN" and me["category"] is None, me)

# 8. API 문서
s, h, b = call("GET", "/v3/api-docs")
check("openapi docs", s == 200 and "/auth/login" in b["paths"] and "bearerAuth" in b["components"]["securitySchemes"], s)

# 9. 레이트 리밋(같은 IP+username 분당 5회)
codes = [call("POST", "/auth/login", {"username": "customer-center.customer", "password": "bad"})[0] for _ in range(6)]
s, h, b = call("POST", "/auth/login", {"username": "customer-center.customer", "password": "bad"})
check("login rate limited 429", codes[:5] == [401] * 5 and codes[5] == 429 and s == 429
      and b["code"] == "RATE_LIMITED" and "Retry-After" in h and b["retryAfterMs"] == 60000, (codes, b))

print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
