package com.Guo.GuoYelp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.Guo.GuoYelp.dto.Result;
import com.Guo.GuoYelp.entity.Shop;
import com.Guo.GuoYelp.mapper.ShopMapper;
import com.Guo.GuoYelp.service.IShopService;
import com.Guo.GuoYelp.utils.CacheClient;
import com.Guo.GuoYelp.utils.RedisData;
import com.Guo.GuoYelp.utils.SystemConstants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.Guo.GuoYelp.utils.RedisConstants.*;

/**
 * 店铺查询
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //创建新的线程池，且池中有10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private CacheClient cacheClient;

    /**
     * 查询商户信息
     *
     * @param id 商户id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //存储空对象解决缓存穿透
        //Shop shop = this.setWithPassThrough(id);

        //互斥锁解决缓存击穿
        //Shop shop = this.queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = this.setWithLogicalExpire(id);

        //工具类改良存储空对象解决缓存穿透
        Shop shop = cacheClient
                .setWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, queryId -> this.getById(queryId), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //工具类改良逻辑过期解决缓存击穿
        //Shop shop = cacheClient
        //        .setWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("商铺不存在");
        }

        return Result.ok(shop);
    }

    /**
     * 新增商铺信息
     *
     * @param shop 商铺数据
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        //id不为空时才能更新数据库
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        this.updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok(shop.getId());
    }

    /**
     * 根据商铺类型分页查询商铺信息
     *
     * @param typeId  商铺类型
     * @param current 页码
     * @param x       当前用户所处位置的经度
     * @param y       当前用户所处位置的纬度
     * @return 商铺列表
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要坐标查询
        if (x == null || y == null) {
            //不需要根据坐标查询，则直接查数据库
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询Redis，并按照距离排序、分页
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            //没有查到数据，则返回空
            return Result.ok(Collections.emptyList());
        }
        //进行解析，得到id和距离
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //如果skip之后没有值了，则没必要再进行分页查询了，因为没有数据了
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        //逻辑分页（假分页）
        ArrayList<Long> ids = new ArrayList<>(list.size());//收集所有的id
        HashMap<String, Distance> distanceMap = new HashMap<>(list.size());//将id与距离一一对应
        list.stream().skip(from).forEach(result -> {
            //获得店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获得距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //根据id查询店铺，不能直接用list()，会打乱排序
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = this.query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        //装配每个店铺的距离
        shops.forEach(shop -> {
            //从HashMap中找到对应的距离
            String K = shop.getId().toString();//hashmap中的key
            Distance V = distanceMap.get(K);//hashmap中的value
            double shopDistance = V.getValue();
            shop.setDistance(shopDistance);
        });
        //返回店铺
        return Result.ok(shops);
    }

    /**
     * 上锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return isLock;
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 存储空对象解决缓存穿透
     *
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //从Redis中查询商户信息（以返回String而不是Hash类型为例）
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断商户是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在则直接返回商户信息（需要将String类型的反序列化成对象）
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            //!=null则一定是空串
            return null;
        }
        //不存在则根据id查询数据库
        Shop shop = this.getById(id);
        //数据库中不存在则直接将空值写入Redis
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在则写入Redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回从数据库中查询到的商户信息
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     *
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        Shop shop = null;

        //从Redis中查询商户信息（以返回String而不是Hash类型为例）
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断商户是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在则直接返回商户信息（需要将String类型的反序列化成对象）
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            //是空值则返回错误信息
            return null;
        }

        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取到锁
            if (!isLock) {
                //没有获取到则休眠一会并重试
                Thread.sleep(50);
                return this.queryWithMutex(id);
            }
            //获取到锁先查询Redis是否已经有缓存，如果有则直接返回（二次校验）
            if (StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(key))) {
                return JSONUtil.toBean(stringRedisTemplate.opsForValue().get(key), Shop.class);
            }
            //获取到锁则查询数据库
            shop = this.getById(id);
            //数据库中不存在则直接将空值写入Redis
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库中存在则写入Redis中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }

        //返回从数据库中查询到的商户信息
        return shop;
    }

    /**
     * 逻辑过期解决缓存击穿
     *
     * @param id
     * @return
     */
    private Shop queryWithLogicExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //从Redis中查询商户信息（以返回String而不是Hash类型为例）
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断商户是否存在
        if (StrUtil.isBlank(shopJson)) {
            //未命中则直接返回空
            return null;
        }

        //命中则判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);//获取店铺信息
        LocalDateTime expireTime = redisData.getExpireTime();//获取逻辑过期时间
        //未过期，直接返回信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        //已过期，准备开始重建缓存，先获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        //判断是否获取锁成功
        if (tryLock(lockKey)) {
            //获得锁之后，二次检测缓存是否过期，未过期则直接返回
            if (expireTime.isAfter(LocalDateTime.now())) {
                shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
                return shop;
            }
            //开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    this.unLock(lockKey);
                }
            });
        }

        //否则返回过期的信息（如果已过期，主线程继续返回过期的信息，副线程进行缓存的重建）
        return shop;
    }

    /**
     * 1、将热点信息（店铺信息）提前存放到redis中
     * 2、独立线程的缓存重建
     *
     * @param id            店铺id
     * @param expireSeconds 逻辑过期时间
     */
    private void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = this.getById(id);
        //设置缓存重建的延迟时间
        Thread.sleep(20);
        //封装逻辑过期时间与热点数据（店铺信息）
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入Redis，不设置TTL
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
    }
}
