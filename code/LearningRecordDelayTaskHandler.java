package com.tianji.learning.utils;

import com.alibaba.fastjson.JSON;
import com.tianji.common.utils.JsonUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.LearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * @ClassName LearningRecordDelayTaskHandler
 * @Description TODO
 * @Author XMING
 * @Date 2023/6/20 16:04
 * @Version 1.0
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class LearningRecordDelayTaskHandler {

    private final StringRedisTemplate redisTemplate;
    private final DelayQueue<DelayTask<RecordTaskData>> delayQueue = new DelayQueue<>();
    public final static String RECORD_KEY = "learning:record:{}";
    private final LearningRecordMapper recordMapper;
    private final LearningLessonService lessonService;
    private static volatile boolean begin = true;

    @PostConstruct
    public void init(){
        log.info("线程池模式");
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        executorService.submit(this::handleDelayTask);
    }

    @PreDestroy
    public void destroy(){
        begin = false;
        log.debug("延迟任务停止执行");
    }

    /**
     * 处理到期任务
     */
    public void handleDelayTask(){
            // 取出任务,阻塞队列
            while(begin){
                try {
                    DelayTask<RecordTaskData> recordTask = delayQueue.take();
                    // 查询redis缓存
                    RecordTaskData recordData = recordTask.getData();
                    LearningRecord record = getLearningRecordFromCache(recordData.getLessonId(), recordData.getSectionId());
                    if(record == null){
                        continue;
                    }
                    // 检查record与缓存的moment是否一致
                    if (!recordData.getMoment().equals(record.getMoment())) {
                        continue;
                    }
                    // 更新学习记录
                    record.setFinished(null);
                    recordMapper.updateById(record);
                    // 更新课表最近学习小节以及时间
                    lessonService.lambdaUpdate()
                            .eq(LearningLesson::getId,recordData.getLessonId())
                            .set(LearningLesson::getLatestLearnTime,LocalDateTime.now())
                            .set(LearningLesson::getLatestSectionId,recordData.getSectionId())
                            .update();
                }
                catch (InterruptedException e) {
                    log.error("处理到期任务异常:{}",e.getMessage());
                }
            }
    }





    public void addLearningRecordTask(LearningRecord record){
        // 添加播放记录到redis缓存
        writeRecordCache(record);
        // 提交延迟任务到延迟队列
        delayQueue.add(new DelayTask<>(Duration.ofSeconds(20),new RecordTaskData(record)));
    }

    public void writeRecordCache(LearningRecord record){
        log.debug("更新学习记录的缓存数据");
        try{
            RecordCacheData recordCacheData = new RecordCacheData(record);
            String recordJson = JSON.toJSONString(recordCacheData);
            String key = StringUtils.format(RECORD_KEY,record.getLessonId());
            redisTemplate.opsForHash().put(key,record.getSectionId().toString(),recordJson);
            // 添加缓存过期时间
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }catch (Exception e){
            log.error("更新学习记录缓存异常");
        }
    }

    /**
     * 从redis缓存中获取学习记录
     * @param lessonId
     * @param sectionId
     * @return
     */
    public LearningRecord getLearningRecordFromCache(Long lessonId,Long sectionId){
        // 从redis缓存中获取数据
        log.debug("从redis缓存中获取学习记录");
        try {
            String key = StringUtils.format(RECORD_KEY,lessonId);
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if(cacheData == null){
                return null;
            }
            return JsonUtils.toBean(cacheData.toString(), LearningRecord.class);
        }catch (Exception e){
            log.error("缓存读取异常:{}",e.getMessage());
            return null;
        }
    }

    /**
     * 清理redis缓存
     */
    public void deleteRecordCache(Long lessonId,Long sectionId){
        log.debug("删除学习记录");
        try {
            String key = StringUtils.format(RECORD_KEY,lessonId);
            redisTemplate.opsForHash().delete(key,sectionId.toString());
        }catch (Exception e){
            log.error("删除缓存异常:{}",e.getMessage());
        }
    }

    /**
     * 存储进redis缓存中的数据
     */
    @Data
    @NoArgsConstructor
    private static class RecordCacheData{
        private Long id;
        private Integer moment;
        private Boolean finished;
        public RecordCacheData(LearningRecord record){
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }

    @Data
    @NoArgsConstructor
    private static class RecordTaskData{
        private Long lessonId;
        private Long sectionId;
        private Integer moment;
        public RecordTaskData(LearningRecord record){
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}
