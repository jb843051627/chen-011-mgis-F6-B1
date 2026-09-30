package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

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

    /** 每日派发时辰窗（GMT+8 整点，两端含）：只在 02:00–05:59 之间落闸，错峰不扰下游通道 */
    private static final int WIN_FROM = 2;
    private static final int WIN_TO = 5;
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

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
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /**
     * 该时刻可处理的条目，口径与 {@link #runOnce(Date)} 完全一致，三条同时成立：
     * 1）在执行时辰窗内（GMT+8 的 WIN_FROM..WIN_TO 点，两端含）；
     * 2）已到提醒点：at &gt;= due_at - amount 个自然日（正卡在提醒点上的那条也算到期）；
     * 3）尚未处理：del_flag=0 且 status=0（已提醒/送不出去的下一轮不再捞起）。
     */
    @Override
    public List<TMgisDutyTask> listDue(Date at) {
        List<TMgisDutyTask> due = new java.util.ArrayList<TMgisDutyTask>();
        if (at == null || !inRunWindow(at)) {
            return due;
        }
        List<TMgisDutyTask> all = this.mgisDutyTaskMapper.selectList(new QueryWrapper<TMgisDutyTask>()
                .eq("del_flag", 0)
                .eq("status", STATUS_WAIT)
                .orderByAsc("due_at"));
        for (TMgisDutyTask r : all) {
            if (r.getDueAt() != null && isRemindDue(r, at)) {
                due.add(r);
            }
        }
        return due;
    }

    /**
     * 执行一次，返回**成功条数**；单条失败跳过继续，不让一条坏数据掀翻整桌。
     * 派发成功落 status=1（已提醒），派发不出去落 status=2（送不出去），
     * 状态一律落库——下轮只捞 status=0，办没办过库里有影。
     * 无件可办（或不在执行时辰窗内）的轮次安安稳稳报 0。
     */
    @Override
    public int runOnce(Date at) {
        List<TMgisDutyTask> due = listDue(at);
        int ok = 0;
        for (TMgisDutyTask r : due) {
            if (!deliver(r)) {
                markStatus(r, STATUS_FAIL);
                continue;
            }
            if (markStatus(r, STATUS_DONE) > 0) {
                ok++;
            } else {
                markStatus(r, STATUS_FAIL);
            }
        }
        return ok;
    }

    /**
     * 派发动作本身（提醒通道暂未接入时，至少要能凭单号落账）：
     * 无单号的条目无处可投，按送不出去处理。单条抛错不影响整批。
     */
    private boolean deliver(TMgisDutyTask r) {
        try {
            return r.getItemNo() != null && !r.getItemNo().trim().isEmpty();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private int markStatus(TMgisDutyTask r, int status) {
        try {
            r.setStatus(status);
            r.setUpdateTime(new Date());
            return this.mgisDutyTaskMapper.updateById(r);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** 是否已到提醒点：执行时刻不早于（到期时刻 - 提前自然日数）；边界含，用墙钟字符串同校 */
    private static boolean isRemindDue(TMgisDutyTask r, Date at) {
        BigDecimal leadDays = r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
        long offset = leadDays.multiply(BigDecimal.valueOf(MILLIS_PER_DAY)).longValue();
        Date remindAt = new Date(r.getDueAt().getTime() - offset);
        return ts(at).compareTo(ts(remindAt)) >= 0;
    }

    /** 执行时辰窗：GMT+8 下整点落在 [WIN_FROM, WIN_TO]，两端含 */
    private static boolean inRunWindow(Date at) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"));
        c.setTime(at);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        return hour >= WIN_FROM && hour <= WIN_TO;
    }
}
