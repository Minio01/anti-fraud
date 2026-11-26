-- KEYS: win, small, last, prof, dev, ip, cty, feature-seen (для данного txId).
-- ARGV: txId, timestampMs, amountMinor, merchantId, deviceId, ip, country, lat, lon.
local win, small, last, profile, devices, ips, countries, seen = unpack(KEYS)
local txId, timestampMs, amountMinor, merchantId, deviceId, ip, country, lat, lon = unpack(ARGV)
local now = tonumber(timestampMs)
local minute = 60000

if redis.call('EXISTS', seen) == 1 then
    return {0}
end

local function exclusive(timestamp)
    return '(' .. string.format('%.0f', timestamp)
end

local function countSince(key, windowMinutes)
    return redis.call('ZCOUNT', key, exclusive(now - windowMinutes * minute), timestampMs)
end

local function sumAmounts(members)
    -- Lua использует double. Складываем центы частями, чтобы не округлять int64.
    local base = 10000000
    local low, middle, high = 0, 0, 0
    for _, member in ipairs(members) do
        local amount = string.sub(member, 38)
        low = low + tonumber(string.sub(amount, -7))
        middle = middle + (tonumber(string.sub(amount, -14, -8)) or 0) + math.floor(low / base)
        high = high + (tonumber(string.sub(amount, 1, -15)) or 0) + math.floor(middle / base)
        low, middle = low % base, middle % base
    end
    if high ~= 0 then
        return string.format('%.0f%07d%07d', high, middle, low)
    end
    if middle ~= 0 then
        return string.format('%d%07d', middle, low)
    end
    return tostring(low)
end

local function countDistinctMerchants(members)
    local seen = {}
    local count = 0

    for _, member in ipairs(members) do
        local name = string.sub(member, 38)
        if not seen[name] then
            seen[name] = true
            count = count + 1
        end
    end
    return count
end

local previous = redis.call('HMGET', last, 'timestamp_ms', 'latitude', 'longitude')
local previousTime = tonumber(previous[1])
local watermark = math.max(now, previousTime or now)
local hourCutoff = watermark - 60 * minute
local smallCutoff = watermark - 10 * minute

-- Запоздавшее событие не откатывает границы хранения и последнее гео.
redis.call('ZREMRANGEBYSCORE', win, '-inf', string.format('%.0f', hourCutoff))
redis.call('ZREMRANGEBYSCORE', small, '-inf', string.format('%.0f', smallCutoff))
if now > hourCutoff then
    redis.call('ZADD', win, timestampMs, txId .. ':' .. amountMinor)
    redis.call('EXPIRE', win, 3600)
end
if now > smallCutoff and tonumber(amountMinor) < 200 then
    redis.call('ZADD', small, timestampMs, txId .. ':' .. merchantId)
    redis.call('EXPIRE', small, 600)
end

local travel = {'', '', ''}
if not previousTime or now >= previousTime then
    if previousTime and now > previousTime and previous[2] and previous[3] then
        travel = {previous[1], previous[2], previous[3]}
    end
    redis.call('HSET', last, 'timestamp_ms', timestampMs)
    if lat ~= '' and lon ~= '' then
        redis.call('HSET', last, 'latitude', lat, 'longitude', lon)
    else
        redis.call('HDEL', last, 'latitude', 'longitude')
    end
    redis.call('EXPIRE', last, 86400)
end

local recent = redis.call('ZRANGEBYSCORE', win, exclusive(now - 5 * minute), timestampMs)
local recentSmall = redis.call('ZRANGEBYSCORE', small, exclusive(now - 10 * minute), timestampMs)
local stats = redis.call('HMGET', profile, 'avg_amount_minor', 'tx_count')
local result = {
    1, countSince(win, 1), #recent, countSince(win, 60), sumAmounts(recent),
    #recentSmall, countDistinctMerchants(recentSmall), travel,
    stats[1] or '', stats[2] or '0',
    1 - redis.call('SISMEMBER', devices, deviceId),
    1 - redis.call('SISMEMBER', ips, ip),
    1 - redis.call('SISMEMBER', countries, country)
}
redis.call('SET', seen, '1', 'EX', 86400)
return result
