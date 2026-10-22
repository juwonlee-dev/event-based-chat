import datetime, json, os, sys, urllib.error, urllib.parse, urllib.request

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
results = []


def call(method, path, body=None, token=None, params=None):
    if params:
        path += "?" + urllib.parse.urlencode(params, doseq=True)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw else None)


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + json.dumps(detail, ensure_ascii=False)[:600]))


def login(u):
    return call("POST", "/auth/login", {"username": u, "password": "chat1234!"})[1]["accessToken"]


def all_pages(token, params, limit):
    items, cursor, pages = [], None, 0
    while True:
        p = dict(params, limit=limit)
        if cursor:
            p["cursor"] = cursor
        s, page = call("GET", "/sessions", token=token, params=p)
        assert s == 200, (s, page)
        items += page["items"]
        pages += 1
        if not page["hasMore"]:
            assert page["nextCursor"] is None
            return items, pages
        cursor = page["nextCursor"]


T = login("telecom.user")
CAR = login("car-sales.customer")
me_t = call("GET", "/users/me", token=T)[1]["id"]
tag = datetime.datetime.now().strftime("%H%M%S")
titles = [f"요금제 변경 {tag}", f"Roaming ABC {tag}", f"100% 환불 {tag}", f"단말 할부 {tag}"]
ids = []
for t in titles:
    s, v = call("POST", "/sessions", {"title": t}, T)
    ids.append(v["id"])
s, v = call("POST", "/sessions", {"title": f"중고차 시세 {tag}"}, CAR)
car_id = v["id"]
call("POST", f"/sessions/{ids[0]}/join", None, CAR)       # IN_PROGRESS
call("POST", f"/sessions/{ids[1]}/leave", None, T)        # SUSPENDED (T 퇴장)

# 분류코드
items, _ = all_pages(T, {"category": "TELECOM", "title": tag}, 100)
check("category filter", {i["id"] for i in items} == set(ids) and all(i["category"] == "TELECOM" for i in items),
      [i["title"] for i in items])
items, _ = all_pages(T, {"category": ["TELECOM", "CAR_SALES"], "title": tag}, 100)
check("multi category filter", {i["id"] for i in items} == set(ids) | {car_id}, [i["title"] for i in items])

# 제목: 부분 일치, 대소문자 무시, 와일드카드 이스케이프
items, _ = all_pages(T, {"title": f"abc {tag}"}, 100)
check("title ignore case", [i["id"] for i in items] == [ids[1]], [i["title"] for i in items])
items, _ = all_pages(T, {"title": f"0% 환불 {tag}"}, 100)
check("title with % matches literally", [i["id"] for i in items] == [ids[2]], [i["title"] for i in items])
s, page = call("GET", "/sessions", token=T, params={"title": "%_"})
check("title '%_' is not a wildcard", s == 200 and all("%_" in i["title"] for i in page["items"]),
      [i["title"] for i in page["items"]])

# 상태
items, _ = all_pages(T, {"title": tag, "status": "WAITING"}, 100)
check("status WAITING", {i["id"] for i in items} == {ids[2], ids[3], car_id}, [i["title"] for i in items])
items, _ = all_pages(T, {"title": tag, "status": ["IN_PROGRESS", "SUSPENDED"]}, 100)
check("status multi", {i["id"] for i in items} == {ids[0], ids[1]}, [(i["title"], i["status"]) for i in items])

# participant=me: 퇴장한 세션은 빠진다
items, _ = all_pages(CAR, {"title": tag, "participant": "me"}, 100)
check("participant=me (car)", {i["id"] for i in items} == {ids[0], car_id}, [i["title"] for i in items])
items, _ = all_pages(T, {"title": tag, "participant": "me"}, 100)
check("participant=me excludes left session", {i["id"] for i in items} == {ids[0], ids[2], ids[3]},
      [i["title"] for i in items])

# 참여자 정보
s, page = call("GET", "/sessions", token=T, params={"title": titles[0]})
p = page["items"][0]["participants"]
check("summary has participants with names", len(p) == 2 and p[0]["userId"] == me_t and p[1]["displayName"]
      and {"userId", "displayName", "role", "category", "state"} == set(p[0]), p)

# 기간(KST, 양 끝 포함)
today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date()
yesterday = today - datetime.timedelta(days=1)
items, _ = all_pages(T, {"title": tag, "createdFrom": today.isoformat(), "createdTo": today.isoformat()}, 100)
check("created today (inclusive)", len(items) == 5, len(items))
items, _ = all_pages(T, {"title": tag, "createdTo": yesterday.isoformat()}, 100)
check("createdTo yesterday excludes", len(items) == 0, len(items))
items, _ = all_pages(T, {"title": tag, "createdFrom": (today + datetime.timedelta(days=1)).isoformat()}, 100)
check("createdFrom tomorrow excludes", len(items) == 0, len(items))

# 키셋 페이징: 작은 페이지로 끝까지 읽은 결과 = 한 번에 읽은 결과
big, _ = all_pages(T, {}, 100)
small, pages = all_pages(T, {}, 3)
order_ok = all((a["createdAt"], a["id"]) >= (b["createdAt"], b["id"]) for a, b in zip(big, big[1:]))
check("keyset pages equal single page", [i["id"] for i in small] == [i["id"] for i in big] and pages > 1,
      (len(small), len(big), pages))
check("sorted createdAt desc, id desc", order_ok)

# 커서 오류
s, page = call("GET", "/sessions", token=T, params={"limit": 1, "status": "WAITING"})
cur = page["nextCursor"]
s, e = call("GET", "/sessions", token=T, params={"limit": 1, "status": "COMPLETED", "cursor": cur})
check("cursor with other filter -> 400 CURSOR_INVALID", s == 400 and e["code"] == "CURSOR_INVALID", (s, e))
s, e = call("GET", "/sessions", token=T, params={"cursor": "garbage!!"})
check("garbage cursor -> 400 CURSOR_INVALID", s == 400 and e["code"] == "CURSOR_INVALID", (s, e))

# 검증
for name, params in [("title 1 char", {"title": "a"}), ("from > to", {"createdFrom": "2026-10-22", "createdTo": "2026-10-21"}),
                     ("bad date", {"createdFrom": "2026/10/21"}), ("bad status", {"status": "FOO"}),
                     ("bad participant", {"participant": "you"}), ("limit 0", {"limit": 0}), ("limit 101", {"limit": 101})]:
    s, e = call("GET", "/sessions", token=T, params=params)
    check(f"{name} -> 400", s == 400 and e["code"] == "VALIDATION_FAILED", (s, e))

s, page = call("GET", "/sessions", token=login("admin"), params={"limit": 1})
check("admin can list", s == 200 and len(page["items"]) == 1 and page["hasMore"], (s, page))

print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
