-- 연결 수·유예·presence 전이를 원자적으로 판정한다. ARGV[1]이 동작 이름이다.
--
-- 키(단일 Redis 전제라 스크립트 안에서 만든다. 클러스터로 옮기면 KEYS로 넘겨야 한다)
--   ws:nodes            SET    등록된 nodeId
--   ws:node:{node}      STRING 노드 lease(TTL)
--   ws:nodeconn:{node}  SET    그 노드의 연결 "userId|connId"
--   ws:conn:{user}      HASH   connId → nodeId
--   pr:{user}           HASH   state, ver, since, lastSeen, reason
--   pr:grace            ZSET   userId → 유예 만료 시각(μs)
--   pr:epoch            STRING 이 Redis 인스턴스에 처음 등록한 시각(μs). 재시작하면 사라진다
--
-- 시각은 Redis TIME(μs)이다. ver = max(지금, 이전 ver + 1)이라 동률이나 시계 역행에도 단조 증가한다.
-- 결과는 전이마다 5칸(userId, 종류, ver, a, b)을 이어 붙인 평면 배열이다.
--   ONLINE           a = 이전에 오프라인이 된 시각(없으면 ''), b = 지금
--   OFFLINE          a = 끊긴 이유, b = 마지막 연결이 끊긴 시각
--   GRACE_CANCELLED  유예 중에 다시 붙어 아무것도 기록하지 않음
--   (reconcile)      종류 = 실제 상태, a = 'RECONCILED'

local op = ARGV[1]
local out = {}

local function fmt(n)
  return string.format('%.0f', n)
end

local function now_us()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000000 + tonumber(t[2])
end

local function push(user, kind, ver, a, b)
  out[#out + 1] = user
  out[#out + 1] = kind
  out[#out + 1] = ver
  out[#out + 1] = a
  out[#out + 1] = b
end

local function next_ver(pr, now, min_ver)
  local prev = tonumber(redis.call('HGET', pr, 'ver') or '0')
  return math.max(now, prev + 1, min_ver or 0)
end

local function connect(user, conn, node, now)
  redis.call('HSET', 'ws:conn:' .. user, conn, node)
  redis.call('SADD', 'ws:nodeconn:' .. node, user .. '|' .. conn)
  if redis.call('ZREM', 'pr:grace', user) == 1 then
    push(user, 'GRACE_CANCELLED', '', '', '')
    return
  end
  local pr = 'pr:' .. user
  if redis.call('HGET', pr, 'state') == 'ONLINE' then
    return
  end
  local since = redis.call('HGET', pr, 'since') or ''
  local ver = next_ver(pr, now)
  redis.call('HSET', pr, 'state', 'ONLINE', 'ver', fmt(ver), 'since', fmt(now))
  push(user, 'ONLINE', fmt(ver), since, fmt(now))
end

-- 마지막 연결이 끊기면(1→0) 바로 OFFLINE으로 바꾸지 않고 유예에 넣는다.
local function disconnect(user, conn, node, now, grace_us, reason)
  redis.call('SREM', 'ws:nodeconn:' .. node, user .. '|' .. conn)
  local conns = 'ws:conn:' .. user
  if redis.call('HDEL', conns, conn) == 0 or redis.call('HLEN', conns) > 0 then
    return
  end
  local pr = 'pr:' .. user
  if redis.call('HGET', pr, 'state') ~= 'ONLINE' then
    return
  end
  redis.call('HSET', pr, 'lastSeen', fmt(now), 'reason', reason)
  redis.call('ZADD', 'pr:grace', fmt(now + grace_us), user)
end

local function split(member)
  local sep = string.find(member, '|', 1, true)
  return string.sub(member, 1, sep - 1), string.sub(member, sep + 1)
end

if op == 'connect' then
  -- user, conn, node
  connect(ARGV[2], ARGV[3], ARGV[4], now_us())

elseif op == 'disconnect' then
  -- user, conn, node, grace(μs), reason
  disconnect(ARGV[2], ARGV[3], ARGV[4], now_us(), tonumber(ARGV[5]), ARGV[6])

elseif op == 'register' then
  -- node, lease ttl(ms), grace(μs), 살아 있는 연결 "userId|connId"...
  -- 노드 lease를 다시 잡고, 메모리에 있는 연결과 Redis 기록을 맞춘다(없는 것은 해제, 있는 것은 다시 등록).
  local node, grace_us, now = ARGV[2], tonumber(ARGV[4]), now_us()
  redis.call('SET', 'ws:node:' .. node, '1', 'PX', ARGV[3])
  redis.call('SADD', 'ws:nodes', node)
  redis.call('SET', 'pr:epoch', fmt(now), 'NX')
  local live = {}
  for i = 5, #ARGV do
    live[ARGV[i]] = true
  end
  for _, member in ipairs(redis.call('SMEMBERS', 'ws:nodeconn:' .. node)) do
    if not live[member] then
      local user, conn = split(member)
      disconnect(user, conn, node, now, grace_us, 'SOCKET_CLOSED')
    end
  end
  for i = 5, #ARGV do
    local user, conn = split(ARGV[i])
    connect(user, conn, node, now)
  end

elseif op == 'sweep' then
  -- grace(μs), 한 번에 꺼낼 최대 수
  local grace_us, limit, now = tonumber(ARGV[2]), ARGV[3], now_us()
  -- lease가 사라진 노드의 연결은 NODE_LOST로 해제한다(유예를 거쳐 OFFLINE).
  for _, node in ipairs(redis.call('SMEMBERS', 'ws:nodes')) do
    if redis.call('EXISTS', 'ws:node:' .. node) == 0 then
      for _, member in ipairs(redis.call('SMEMBERS', 'ws:nodeconn:' .. node)) do
        local user, conn = split(member)
        disconnect(user, conn, node, now, grace_us, 'NODE_LOST')
      end
      redis.call('DEL', 'ws:nodeconn:' .. node)
      redis.call('SREM', 'ws:nodes', node)
    end
  end
  -- 유예가 끝났는데 여전히 연결이 없으면 OFFLINE. 꺼내기와 판정이 한 번에 일어나 여러 노드가 돌려도 한 번만 나온다.
  for _, user in ipairs(redis.call('ZRANGE', 'pr:grace', '-inf', fmt(now), 'BYSCORE', 'LIMIT', 0, limit)) do
    redis.call('ZREM', 'pr:grace', user)
    local pr = 'pr:' .. user
    if redis.call('HLEN', 'ws:conn:' .. user) == 0 and redis.call('HGET', pr, 'state') == 'ONLINE' then
      local ver = next_ver(pr, now)
      redis.call('HSET', pr, 'state', 'OFFLINE', 'ver', fmt(ver), 'since', fmt(now))
      push(user, 'OFFLINE', fmt(ver), redis.call('HGET', pr, 'reason') or 'SOCKET_CLOSED',
        redis.call('HGET', pr, 'lastSeen') or fmt(now))
    end
  end

elseif op == 'reconcile' then
  -- 보류 시간(μs), (userId, DB 상태 ONLINE|OFFLINE|MIXED, DB의 최대 presence_ver)...
  -- 유예 중이 아닌 사용자의 실제 상태(연결 수)를 DB와 비교해, 다르면 그 상태와 DB보다 큰 ver를 돌려준다.
  -- Redis가 재시작해 연결 기록이 비었으면 모든 노드가 재등록할 때까지(첫 등록 뒤 보류 시간) 판정하지 않는다.
  -- 그 전에 판정하면 아직 재등록하지 않은 노드의 사용자가 거짓 OFFLINE으로 기록된다.
  local now = now_us()
  local epoch = redis.call('GET', 'pr:epoch')
  if not epoch or now - tonumber(epoch) < tonumber(ARGV[2]) then
    return out
  end
  for i = 3, #ARGV, 3 do
    local user, db, db_ver = ARGV[i], ARGV[i + 1], tonumber(ARGV[i + 2])
    if not redis.call('ZSCORE', 'pr:grace', user) then
      local pr = 'pr:' .. user
      local actual = redis.call('HLEN', 'ws:conn:' .. user) > 0 and 'ONLINE' or 'OFFLINE'
      local state = redis.call('HGET', pr, 'state')
      local ver = tonumber(redis.call('HGET', pr, 'ver') or '0')
      if state ~= actual or (db ~= actual and ver <= db_ver) then
        ver = next_ver(pr, now, db_ver + 1)
        redis.call('HSET', pr, 'state', actual, 'ver', fmt(ver), 'since', fmt(now))
      end
      if db ~= actual then
        push(user, actual, fmt(ver), 'RECONCILED', '')
      end
    end
  end
end

return out
