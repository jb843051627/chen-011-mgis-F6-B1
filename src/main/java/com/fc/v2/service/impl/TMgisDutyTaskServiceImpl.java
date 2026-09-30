package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TMgisDutyTaskMapper;
import com.fc.v2.model.auto.TMgisDutyTask;
import com.fc.v2.service.ITMgisDutyTaskService;

/**
 * 到期提醒派发条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMgisDutyTaskServiceImpl implements ITMgisDutyTaskService {

    private static final Logger log = LoggerFactory.getLogger(TMgisDutyTaskServiceImpl.class);

    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    /** 派发执行窗口：每天 02:00（含）至 05:00（含），窗口之外的时辰不得派发 */
    private static final int WIN_FROM_HOUR = 2;
    private static final int WIN_TO_HOUR = 5;

    @javax.annotation.Resource
    private TMgisDutyTaskMapper mgisDutyTaskMapper;

    @Override
    public TMgisDutyTask selectTMgisDutyTaskById(Long id) {
        return this.mgisDutyTaskMapper.selectById(id);
    }

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使"恰好到期"这类边界用例错判。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /** 是否落在派发执行时辰窗口内（WIN_FROM_HOUR <= 小时 <= WIN_TO_HOUR） */
    private static boolean inDispatchWindow(Date at) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(at);
        int hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= WIN_FROM_HOUR && hour <= WIN_TO_HOUR;
    }

    @Override
    public List<TMgisDutyTask> listDue(Date at) {
        List<TMgisDutyTask> due = new java.util.ArrayList<TMgisDutyTask>();
        if (at == null || !inDispatchWindow(at)) {
            // 不在派发时点上的时辰不得处理，避免条目白白被处理一茬
            return due;
        }
        // 只捞正常、待提醒的条目，避免下一轮把已办结条目重新捞起
        List<TMgisDutyTask> all = this.mgisDutyTaskMapper.selectList(new QueryWrapper<TMgisDutyTask>()
                .eq("del_flag", 0)
                .eq("status", STATUS_WAIT));
        for (TMgisDutyTask r : all) {
            // 恰好在期点上（due_at == at）也算到期，用 <= 而非 <
            if (r.getDueAt() != null && ts(r.getDueAt()).compareTo(ts(at)) <= 0) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        List<TMgisDutyTask> due = listDue(at);
        if (due.isEmpty()) {
            // 无件可办的轮次报零，不得越界取首元素而摔错
            return 0;
        }
        int ok = 0;
        for (TMgisDutyTask r : due) {
            // 单条异常只波及自身并记"送不出去"，不得掀翻整批
            try {
                dispatch(r);
                r.setStatus(STATUS_DONE);
                r.setUpdateTime(new Date());
                this.mgisDutyTaskMapper.updateById(r);
                ok++;
            } catch (Exception e) {
                log.warn("到期提醒派发失败 itemNo={}", r.getItemNo(), e);
                markFail(r);
            }
        }
        return ok;
    }

    /** 标记送不出去；落库本身再失败也只记日志，保证后续条目继续处理 */
    private void markFail(TMgisDutyTask r) {
        try {
            r.setStatus(STATUS_FAIL);
            r.setUpdateTime(new Date());
            this.mgisDutyTaskMapper.updateById(r);
        } catch (Exception ex) {
            log.warn("到期提醒失败态落库失败 itemNo={}", r.getItemNo(), ex);
        }
    }

    /**
     * 实际派发动作：提前提醒自然日数不得为负，否则视为送不出去。
     * 当前版本消息下发通道暂未接通，校验通过即视为可送达。
     */
    private void dispatch(TMgisDutyTask r) {
        if (r.getAmount() == null || r.getAmount().signum() < 0) {
            throw new IllegalStateException("提前提醒自然日数非法");
        }
    }
}
