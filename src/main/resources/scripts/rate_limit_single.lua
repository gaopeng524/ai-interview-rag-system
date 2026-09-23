-- 多维度滑动窗口限流脚本
-- 基于 Redis ZSET 的毫秒级时间戳原子滑动窗口
-- 同一方法上的多条 @RateLimit 规则会在一次 Lua 调用中完成检查和扣减

-- 参数说明：
-- KEYS[i]: 第 i 条限流维度键 (例如 ratelimit:ChatController:streamChat:ip:127.0.0.1)
-- ARGV[1]: 当前时间戳（毫秒）
-- ARGV[2]: 请求唯一标识 (UUID)
-- ARGV[3]: 规则数量 N
-- 从 ARGV[4] 开始，每条规则 3 个参数：
--   申请令牌数 (permits)、时间窗口毫秒数 (interval_ms)、窗口内最大允许数 (max_tokens)

local now_ms = tonumber(ARGV[1])
local request_id = ARGV[2]
local rule_count = tonumber(ARGV[3])

local current_values = {}
local permit_values = {}
local intervals = {}

-- 第一阶段：只读取并检查可用令牌。任一维度不满足时，不修改任何限流状态，直接返回失败维度序号（负数）。
for i = 1, rule_count do
    local arg_index = 4 + (i - 1) * 3
    local key = KEYS[i]
    local permits = tonumber(ARGV[arg_index])
    local interval = tonumber(ARGV[arg_index + 1])
    local max_tokens = tonumber(ARGV[arg_index + 2])
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"

    local current_val = tonumber(redis.call("get", value_key)) or max_tokens

    -- 清理过期时间戳并恢复令牌
    local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
    if #expired_values > 0 then
        local expired_count = 0
        for _, v in ipairs(expired_values) do
            local p = tonumber(string.match(v, ":(%d+)$"))
            if p then
                expired_count = expired_count + p
            end
        end

        if expired_count > 0 then
            current_val = math.min(max_tokens, current_val + expired_count)
        end
    end

    -- 令牌不足，直接拒绝
    if current_val < permits then
        return -i
    end

    current_values[i] = current_val
    permit_values[i] = permits
    intervals[i] = interval
end

-- 第二阶段：所有维度均检查通过，统一扣减令牌并记录时间戳。
for i = 1, rule_count do
    local key = KEYS[i]
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"
    local permits = permit_values[i]
    local current_val = current_values[i]
    local interval = intervals[i]

    local permit_record = request_id .. ":" .. i .. ":" .. permits
    redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)
    redis.call("zadd", permits_key, now_ms, permit_record)
    redis.call("set", value_key, current_val - permits)

    -- 设置 TTL (窗口时长的 2 倍兜底清理)
    local expire_time = math.ceil(interval * 2 / 1000)
    if expire_time < 1 then expire_time = 1 end
    redis.call("expire", value_key, expire_time)
    redis.call("expire", permits_key, expire_time)
end

return 1
