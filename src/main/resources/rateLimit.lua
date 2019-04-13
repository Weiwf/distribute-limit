local key = "rate.limit:" .. KEYS[1] --限流KEY
local limit = tonumber(ARGV[1])        --限流次数
local current = tonumber(redis.call('get', key) or "0")
if current + 1 > limit then
  return 0
else  --请求数+1，并设置ARGV[2]秒过期
  redis.call("INCRBY", key,"1")
   redis.call("expire", key,ARGV[2])
   return current + 1
end

