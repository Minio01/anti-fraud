-- KEYS: prof, dev, ip, cty, dev-recent, ip-recent, cty-recent, profile-seen.
-- ARGV: amountMinor, deviceId, ip, country.
local profile = KEYS[1]
local seen = KEYS[8]
if redis.call('EXISTS', seen) == 1 then
    return 0
end

local amount = tonumber(ARGV[1])
local average = tonumber(redis.call('HGET', profile, 'avg_amount_minor'))
local updated = average and average + 0.1 * (amount - average) or amount
redis.call('HINCRBY', profile, 'tx_count', 1)
redis.call('HSET', profile, 'avg_amount_minor', string.format('%.17g', updated))

for index = 2, 4 do
    local members, recent, value = KEYS[index], KEYS[index + 3], ARGV[index]
    redis.call('LREM', recent, 0, value)
    redis.call('LPUSH', recent, value)
    redis.call('SADD', members, value)
    local oldest = redis.call('LINDEX', recent, 20)
    if oldest then
        redis.call('SREM', members, oldest)
        redis.call('LTRIM', recent, 0, 19)
    end
end

redis.call('SET', seen, '1', 'EX', 86400)
return 1
