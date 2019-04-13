# 通过redis+lua实现分布式限流

&#160; &#160; &#160; &#160;在秒杀系统中，短时间内会有大量用户进行抢购，会对系统造成巨大的冲击，而抢购的商品数量远远小于需求，因此，只对部分用户的请求进行处理而过滤到大部分用户的请求是必须的。对于秒杀接口，需要做到限制用户的访问频率，拒绝多余的请求，实现限流。限流分为单机限流和分布式限流，单机限流的方法有漏桶算法、令牌桶算法，也可以通过AtomicInteger、Semphore来实现；分布式限流有基于网关层面的的Nginx+lua和基于应用层面的Redis+lua。本文介绍的是Redis+lua的方式。

#### lua脚本
```lua
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
```

结合RedisTemplate的方法execute(RedisScript<T> script, List<K> keys, Object... args)来看，script是我们定义脚本的封装对象，后面会提到，KEYS[1]对应的是keys的第一个key值(注意这里和我们通过数组下标的方式不一样，数组的一个元素是array[0])，ARGV[1]是第一个参数，ARGV[2]是第二个参数，依次类推。在后面的定义中，KEYS[1]对应的是限流key，ARGV[1]对应的是限流次数，ARGV[2]对应的是限流时间。


#### lua脚本的加载和Redis的配置
```java
@Component
public class RedisConfig {
    /**
     * 读取限流脚本
     *
     * @return
     */
    @Bean
    public DefaultRedisScript<Number> redisluaScript() {
        DefaultRedisScript<Number> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("rateLimit.lua")));
        redisScript.setResultType(Number.class);
        return redisScript;
    }

    /**
     * RedisTemplate
     *
     * @return
     */
    @Bean
    public RedisTemplate<String, Serializable> limitRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
        //略
    }
}
```
DefaultRedisScript对应上文提到的定义脚本的封装对象

#### 限流注解
```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流唯一标示
     *
     * @return
     */
    String key() default "";

    /**
     * 限流时间
     *
     * @return
     */
    int time();

    /**
     * 限流次数
     *
     * @return
     */
    int count();
}
```

#### 拦截器
```java
@Aspect
@Component
public class LimitAspect {
    private static final Logger logger = LoggerFactory.getLogger(LimitAspect.class);

    @Autowired
    private RedisTemplate<String, Serializable> limitRedisTemplate;

    @Autowired
    private DefaultRedisScript<Number> redisluaScript;

    @Around("execution(* com.wei.demo.controller ..*(..) )")
    public Object interceptor(ProceedingJoinPoint joinPoint) throws Throwable {

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = method.getDeclaringClass();
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);

        if (rateLimit != null) {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String ipAddress = getIpAddr(request);

            StringBuffer stringBuffer = new StringBuffer();
            stringBuffer.append(ipAddress).append("-")
                    .append(targetClass.getName()).append("-")
                    .append(method.getName()).append("-")
                    .append(rateLimit.key());

            List<String> keys = Collections.singletonList(stringBuffer.toString());

	    //传入脚本对象、限流key、限流次数、限流时间参数
            Number number = limitRedisTemplate.execute(redisluaScript, keys, rateLimit.count(), rateLimit.time());

            if (number != null && number.intValue() != 0 && number.intValue() <= rateLimit.count()) {
                logger.info(rateLimit.time() + "s内能访问" + rateLimit.count() + "次，" + "第：{} 次", number.toString());
                return joinPoint.proceed();
            } else {
                throw new RuntimeException("已经到设置限流次数");
            }

        } else {
            return joinPoint.proceed();
        }
    }

    public static String getIpAddr(HttpServletRequest request) {
        //略
    }
}
```

#### 控制层
```java
/**
 * @author weiwenfeng
 * @date 2019/4/12
 */
@RestController
public class ApiController {

    @Autowired
    private RedisTemplate redisTemplate;

    // 10 秒中，可以访问5次
    @RateLimit(key = "test", time = 10, count = 5)
    @GetMapping("/test")
    public String luaLimiter() {
        //统计接口历史访问量
        RedisAtomicInteger entityIdCounter = new RedisAtomicInteger("entityIdCounter", redisTemplate.getConnectionFactory());

        String date = DateFormatUtils.format(new Date(), "yyyy-MM-dd HH:mm:ss.SSS");

        return date + " 累计访问次数：" + entityIdCounter.getAndIncrement();
    }
}
```

#### 启动应用
浏览器访问：http://127.0.0.1:8080/test，10s内只能访问5次，超过10s后归0重新计数

日志
```
2019-04-13 13:39:39.719  INFO 8424 --- [nio-8080-exec-7] com.wei.demo.aspect.LimitAspect          : 10s内能访问5次，第：1 次
2019-04-13 13:39:40.368  INFO 8424 --- [nio-8080-exec-9] com.wei.demo.aspect.LimitAspect          : 10s内能访问5次，第：2 次
2019-04-13 13:39:41.106  INFO 8424 --- [nio-8080-exec-8] com.wei.demo.aspect.LimitAspect          : 10s内能访问5次，第：3 次
2019-04-13 13:39:41.727  INFO 8424 --- [io-8080-exec-10] com.wei.demo.aspect.LimitAspect          : 10s内能访问5次，第：4 次
2019-04-13 13:39:42.423  INFO 8424 --- [nio-8080-exec-2] com.wei.demo.aspect.LimitAspect          : 10s内能访问5次，第：5 次
2019-04-13 13:39:43.535 ERROR 8424 --- [nio-8080-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed; nested exception is java.lang.RuntimeException: 已经到设置限流次数] with root cause

java.lang.RuntimeException: 已经到设置限流次数
	at com.wei.demo.aspect.LimitAspect.interceptor(LimitAspect.java:64) ~[classes/:na]
	at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method) ~[na:1.8.0_101]
	at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62) ~[na:1.8.0_101]
	at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43) ~[na:1.8.0_101]
	at java.lang.reflect.Method.invoke(Method.java:498) ~[na:1.8.0_101]
```
