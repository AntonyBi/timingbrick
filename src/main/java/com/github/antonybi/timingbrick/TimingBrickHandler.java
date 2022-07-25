package com.github.antonybi.timingbrick;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.PostConstruct;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 本算法是基于snow flake和sony flake的改进版，命名为timed brick算法。
 * bit位分配：1位符号位，38位时间戳，11位序列号，14位段号。
 * 其中，时间戳单位为16ms，最大时间可至2109年，序列号每16ms可生成2048个序号，每秒可生成128000个，段共有16384个。
 * 段由work主动申请，由redis lua脚本进行实时分配。
 * <p>
 * 这个类是非线程安全的，调用时需加锁。如果遇到异常，外部程序需休眠一段时间重试。
 * 未做特殊说明，本程序时间单位均是ms
 *
 * @author antonybi
 * @since 2021-07-15
 */
@Component
@Slf4j
public class TimingBrickHandler {

    /**
     * 步长设置为4，这是一个魔法数字。
     * 一个时间片为16ms，尽量复用减少浪费。
     * 每次申请brick，有效时长占用10s时间长度的brick，避免过于频繁的申请。
     */
    private static final int STEP_LENGTH = 4;

    /**
     * 一个时间片中最多能产生的序号数量
     */
    private static final int MAX_COUNT = 2048;

    /**
     * 当前的段号
     */
    private long brickCourse;

    /**
     * 当前段的授权结束时间
     */
    private long brickEndTime;

    /**
     * 当前时间片生成ID的计数器
     */
    private int counter;

    /**
     * 上一次的时间片，用来判断是否跨时间片重新计数
     */
    private long lastTimeSlice;

    /**
     * 服务器获取时间与本地的时间差
     */
    private long serverTimeOffset;

    /**
     * 上一次生成的时间，用来判断本地的时钟回拨
     */
    private long lastFetchTime;

    /**
     * lua脚本读取的byte数组
     */
    private byte[] scriptBytes;

    private JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);

    @PostConstruct
    public void init() {
        URL scriptUrl = this.getClass().getResource("/timing_brick.lua");
        if (scriptUrl == null) {
            log.error("cannot find timing_brick.lua");
            System.exit(0);
        }
        try {
            scriptBytes = FileUtils.readFileToByteArray(new File(scriptUrl.toURI()));
        } catch (Exception e) {
            log.error("cannot read timing_brick.lua", e);
            System.exit(0);
        }
    }

    @Value("${spring.application.name:unknown}")
    private String appName;

    private boolean fallback = false;

    /**
     * 每次申请授权续期的时长，范围在1s到30m
     */
    @Value("${guid.authDuration:10m}")
    private Duration authDuration;

    public long fetch() throws TimingBrickException {
        long currentAdjustedTime = getCurrentAdjustedTime();

        // 检查本地时钟是否出现回拨
        if (currentAdjustedTime < lastFetchTime) {
            if (!fallback) {
                // 首次检测到回拨
                log.warn("time fall back: now is {}, last is {}", currentAdjustedTime, lastFetchTime);
                fallback = true;
            }
            if (counter >= MAX_COUNT) {
                // 消耗完当前时间片的序号就重新申请号段，避免等待
                applyForNewBrick();
            }
            return counter++;
        }

        lastFetchTime = currentAdjustedTime;
        fallback = false;

        if (currentAdjustedTime >= brickEndTime) {
            log.debug("brick timeout: {}", brickCourse);
            applyForNewBrick();
            return counter++;
        }

        long currentTimeSlice = currentAdjustedTime >> STEP_LENGTH;
        if (currentTimeSlice > lastTimeSlice) {
            log.debug("time slice timeout: {}", lastTimeSlice);
            lastTimeSlice = currentTimeSlice;
            counter = 0;
            return counter++;
        }

        if (counter >= MAX_COUNT) {
            long nextTimeSliceTime = (currentTimeSlice + 1) << STEP_LENGTH;
            log.debug("waiting for next time slice: {}", nextTimeSliceTime);
            try {
                Thread.sleep(nextTimeSliceTime - currentAdjustedTime);
            } catch (InterruptedException e) {
                log.info("interrupted", e);
                Thread.currentThread().interrupt();
            }
            return fetch();
        }

        return counter++;
    }

    private void applyForNewBrick() throws TimingBrickException {
        List<Long> response;
        try (Jedis jedis = jedisPool.getResource()) {
            response = (List<Long>) jedis.eval(scriptBytes,
                    0,
                    String.valueOf(authDuration.getSeconds() * 1000).getBytes(StandardCharsets.UTF_8),
                    ("[" + appName + "]" + authDuration).getBytes(StandardCharsets.UTF_8));
        }

        Assert.notNull(response, "redis response is null");
        if (response.get(0).equals(0L)) {
            throw new TimingBrickException("cannot apply for new brick: " + Arrays.toString(response.toArray()));
        }

        brickCourse = response.get(1);
        long brickStartTime = response.get(2);
        brickEndTime = response.get(3);
        // 计算本地时间与brick时间差值，充分利用获取到的授权期
        serverTimeOffset = brickStartTime - System.currentTimeMillis();

        log.info("Got a new brick! [brickCourse: {}, startTime: {}, endTime: {}, timeOffset: {}]",
                brickCourse, brickStartTime, brickEndTime, serverTimeOffset);

        lastTimeSlice = getCurrentAdjustedTime() >> STEP_LENGTH;
        counter = 0;
    }

    private long getCurrentAdjustedTime() {
        return System.currentTimeMillis() + serverTimeOffset;
    }

    long getBrickCourse() {
        return brickCourse;
    }

    long getLastTimeSlice() {
        return lastTimeSlice;
    }

}
