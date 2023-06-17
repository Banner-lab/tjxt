package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import java.util.HashMap;

import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningLessonVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.service.LearningLessonService;
import com.tianji.learning.mapper.LearningLessonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
* @author XMING
* @description 针对表【learning_lesson(学生课程表)】的数据库操作Service实现
* @createDate 2023-06-17 18:02:24
*/
@RequiredArgsConstructor
@Service
@Slf4j
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson>
    implements LearningLessonService{


    private CourseClient courseClient;

    private CatalogueClient catalogueClient;

    /**
     * 存储课表信息到learinglesson表
     */
    @Override
    public void saveLesson(Long userId, List<Long> courseIds) {
        //远程调用courseClient查询课程信息
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(courseIds);
        if(CollUtils.isEmpty(simpleInfoList)){
            log.error("课程信息不存在，无法保存课表");
            return ;
        }
        // 获取课程有效期
        List<LearningLesson> lessons = new ArrayList<LearningLesson>(simpleInfoList.size());
        simpleInfoList.forEach(s->{
            LearningLesson lesson = new LearningLesson();
            Integer validDuration = s.getValidDuration();
            if(validDuration != null && validDuration >0){
                LocalDateTime now = LocalDateTime.now();
                lesson.setCreateTime(now);
                lesson.setExpireTime(now.plusMonths(validDuration));
            }
            lesson.setUserId(userId);
            lesson.setCourseId(s.getId());
            lessons.add(lesson);
        });
        // 保存课表信息到数据库
        saveBatch(lessons);
    }

    /**
     * 分页查询我的课表信息
     * @param pageQuery
     * @return
     */
    @Override
    public PageDTO<LearningLessonVO> getPage(PageQuery pageQuery) {
        // 获取当前登录用户id
        Long userId = UserContext.getUser();
        // 分页查询
        Page<LearningLesson> page = lambdaQuery().eq(LearningLesson::getUserId,userId)
                .page(pageQuery.toMpPage("latest_learn_time",false));
        List<LearningLesson> records = page.getRecords();
        if(CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }
        // 收集课程id
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        // 获取课程信息
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(cIds);
        if(CollUtils.isEmpty(simpleInfoList)){
            throw new BadRequestException("课程信息不存在");
        }
        // 建立课程id和课程信息的映射，方便获取课程信息
        Map<Long, CourseSimpleInfoDTO> cMap = simpleInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        List<LearningLessonVO> result = new ArrayList<LearningLessonVO>(records.size());
        records.forEach(r->{
            LearningLessonVO learningLessonVO = BeanUtils.copyBean(r, LearningLessonVO.class);
            CourseSimpleInfoDTO courseSimpleInfoDTO = cMap.get(r.getCourseId());
            learningLessonVO.setCourseName(courseSimpleInfoDTO.getName());
            learningLessonVO.setCourseCoverUrl(courseSimpleInfoDTO.getCoverUrl());
            learningLessonVO.setSections(courseSimpleInfoDTO.getSectionNum());
            result.add(learningLessonVO);
        });
        return PageDTO.of(page,result);
    }

    /**
     * 获取当前正在学习的课程
     * @return
     */
    @Override
    public LearningLessonVO getLearningLesson() {
        // 获取当前登录用户id
        Long userId = UserContext.getUser();
        LambdaQueryWrapper<LearningLesson> queryWrapper = new LambdaQueryWrapper<>();
        // 根据用户id查询正在学习的课
        queryWrapper.eq(LearningLesson::getUserId,userId);
        queryWrapper.eq(LearningLesson::getStatus, LessonStatus.LEARNING.getValue());
        queryWrapper.orderByDesc(LearningLesson::getLatestLearnTime);
        queryWrapper.last("LIMIT 1");
        LearningLesson lesson = this.getOne(queryWrapper);
        if(lesson == null){
            throw  new BadRequestException("没有正在学习的课程");
        }
        // 获取课程详细信息
        Long courseId = lesson.getCourseId();
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(courseId, false, false);
        if(cInfo == null){
            throw new BadRequestException("没有此课程");
        }
        LearningLessonVO vo = BeanUtils.copyBean(lesson, LearningLessonVO.class);
        vo.setCourseName(cInfo.getName());
        vo.setCourseCoverUrl(vo.getCourseCoverUrl());
        vo.setSections(cInfo.getSectionNum());

        // 统计课程表中课程数目
        Integer courseNum = lambdaQuery().eq(LearningLesson::getUserId, userId)
                .count();
        vo.setCourseAmount(courseNum);
        // 获取小节信息
        List<CataSimpleInfoDTO> cataInfo = catalogueClient.batchQueryCatalogue(CollUtils.singletonList(lesson.getLatestSectionId()));
        if(!CollUtils.isEmpty(cataInfo)){
            CataSimpleInfoDTO cata = cataInfo.get(0);
            vo.setLatestSectionName(cata.getName());
            vo.setLatestSectionIndex(cata.getCIndex());
        }
        return vo;
    }

    /**
     * 删除课表
     * @param userId
     * @param courseId
     */
    @Override
    public void deleteLesson(Long userId, Long courseId) {
        if(userId == null){
            userId = UserContext.getUser();
        }
        LambdaQueryWrapper<LearningLesson> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(LearningLesson::getUserId,userId);
        queryWrapper.eq(LearningLesson::getCourseId,courseId);
        this.remove(queryWrapper);
    }

    /**
     * 检查课表中课程状态是否有效
     * 判断一句为：
     *  用户课表中是否有该课程
     *  课程状态是否是有效的状态
     * @param courseId
     * @return
     */
    @Override
    public Long checkLessonValid(Long courseId) {
        // 获取登录用户id
        Long userId = UserContext.getUser();
        LambdaQueryWrapper<LearningLesson> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(LearningLesson::getCourseId,courseId);
        queryWrapper.eq(LearningLesson::getUserId,userId);
        queryWrapper.ne(LearningLesson::getStatus,LessonStatus.EXPIRED.getValue());
        LearningLesson lesson = this.getOne(queryWrapper);
        if(lesson == null){
            return null;
        }
        return lesson.getId();
    }

    /**
     * 查询用户课表中指定课程状态
     * @param courseId
     * @return
     */
    @Override
    public LearningLesson getLessonStatus(Long courseId) {
        // 获取登录用户id
        Long userId = UserContext.getUser();
        if(userId == null){
            return null;
        }
        return lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
    }

    /**
     * 统计课程的学习人数
     * @param courseId
     * @return
     */
    @Override
    public Integer countLearningLessonByCourse(Long courseId) {
        return lambdaQuery().eq(LearningLesson::getCourseId, courseId)
                .ne(LearningLesson::getStatus, LessonStatus.EXPIRED.getValue())
                .count();
    }

}




